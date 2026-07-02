;;; kawaforge/mixin.scm --- define-mixin DSL for Kawa-authored Sponge Mixins.
;;;
;;; v1. Surface syntax: docs/mixin-dsl-spec.md.
;;; Expansion target: plain define-simple-class + carrier annotations whose
;;; payloads follow docs/mixin-payload-spec.md. All payload strings are built
;;; at macro-expansion time, so they are constants by construction.
;;;
;;; Structure note: the helpers live at module level (not inside the
;;; transformer) because Kawa's transformer evaluator does not reliably handle
;;; large internal-define clusters; module functions are available to macros
;;; at consumer compile time.

(module-name (kawaforge mixin))
(module-export define-mixin)

;; These are zero-arg FUNCTIONS, not top-level constants, on purpose: when the
;; consumer module is compiled, this module's transformer runs before the
;; module body has been executed, so plain top-level variables are still
;; unbound locations. Functions compile to static methods and resolve fine.
;; Also: '@' is Kawa's splicing operator, so a bare '@ literal does not read
;; as a quoted symbol — build it explicitly.
(define (kfm:at-sym) (string->symbol "@"))
(define (kfm:value-kw) 'value:)
(define (kfm:class-carrier)
  (string->symbol "@com.momosoftworks.kawaforge.mixin.KawaMixinMeta"))
(define (kfm:member-carrier)
  (string->symbol "@com.momosoftworks.kawaforge.mixin.KawaMemberMeta"))
(define (kfm:inject-heads)
  '(method at cancellable require expect allow remap id constraints))
(define (kfm:at-shift-enum) 'org.spongepowered.asm.mixin.injection.At$Shift)

(define (kfm:fail cname msg irritants)
  (apply error
         (string-append "define-mixin " (symbol->string cname) ": " msg)
         irritants))

;; ---- payload serializer (payload grammar subset; not `write`, which may
;;      bar-quote symbols like @) ----
(define (kfm:join strs sep)
  (if (null? strs)
      ""
      (let loop ((acc (car strs)) (rest (cdr strs)))
        (if (null? rest)
            acc
            (loop (string-append acc sep (car rest)) (cdr rest))))))

(define (kfm:ser cname x)
  (cond
   ((pair? x)
    (string-append "(" (kfm:join (map (lambda (e) (kfm:ser cname e)) x) " ") ")"))
   ((null? x) "()")
   ((string? x) (call-with-output-string (lambda (p) (write x p))))
   ((symbol? x) (symbol->string x))
   ((eq? x #t) "#t")
   ((eq? x #f) "#f")
   ((and (integer? x) (exact? x)) (number->string x))
   ((real? x) (number->string x))
   ((char? x) (string-append "#\\" (string x)))
   (else (kfm:fail cname "value not expressible in a mixin payload:" (list x)))))

(define (kfm:check-params cname mname params)
  (if (not (list? params))
      (kfm:fail cname
                (string-append (symbol->string mname) ": parameter list expected")
                (list params)))
  (for-each
   (lambda (p)
     (if (not (and (pair? p) (list? p) (= (length p) 3)
                   (symbol? (car p)) (eq? (cadr p) '::)))
         (kfm:fail cname
                   (string-append (symbol->string mname)
                                  ": parameter must be (name :: type), got")
                   (list p))))
   params))

(define (kfm:member-annotation cname payload)
  (list (kfm:member-carrier) (kfm:value-kw) (kfm:ser cname payload)))

(define (kfm:at->payload cname at-args)
  (cond
   ;; (at "HEAD") shorthand
   ((and (= (length at-args) 1) (string? (car at-args)))
    (list (kfm:at-sym) 'At (list 'value (car at-args))))
   ;; (at (value "INVOKE") (target ...) (shift AFTER) ...) full form
   ((and (pair? at-args) (pair? (car at-args)))
    (cons (kfm:at-sym)
          (cons 'At
                (map (lambda (m)
                       (cond
                        ((not (and (pair? m) (symbol? (car m))))
                         (kfm:fail cname "bad @At member clause:" (list m)))
                        ((eq? (car m) 'shift)
                         (if (not (and (= (length m) 2) (symbol? (cadr m))))
                             (kfm:fail cname "(shift SYM) expected, got:" (list m)))
                         (list 'shift (list 'enum (kfm:at-shift-enum) (cadr m))))
                        (else m)))
                     at-args))))
   (else (kfm:fail cname "bad (at ...) spec:" (list at-args)))))

(define (kfm:expand-inject cname form)
  (if (< (length form) 3)
      (kfm:fail cname "(inject NAME PARAMS clauses... body...) expected, got:"
                (list form)))
  (let ((mname (cadr form))
        (params (caddr form)))
    (if (not (symbol? mname))
        (kfm:fail cname "inject: handler name expected, got:" (list mname)))
    (kfm:check-params cname mname params)
    (let loop ((forms (cdddr form)) (clauses '()))
      (if (and (pair? forms)
               (pair? (car forms))
               (symbol? (caar forms))
               (memq (caar forms) (kfm:inject-heads)))
          (loop (cdr forms) (cons (car forms) clauses))
          (let ((clauses (reverse clauses))
                (body forms))
            (if (not (assq 'method clauses))
                (kfm:fail cname
                          (string-append (symbol->string mname)
                                         ": (method \"...\") clause is required")
                          '()))
            (if (not (assq 'at clauses))
                (kfm:fail cname
                          (string-append (symbol->string mname)
                                         ": (at ...) clause is required")
                          '()))
            (let ((payload
                   (cons (kfm:at-sym)
                         (cons 'Inject
                               (map (lambda (c)
                                      (if (eq? (car c) 'at)
                                          (list 'at (kfm:at->payload cname (cdr c)))
                                          c))
                                    clauses)))))
              (cons (cons mname params)
                    (cons ':: (cons 'void
                      (cons (kfm:member-annotation cname payload) body))))))))))

(define (kfm:expand-shadow-field cname form)
  (if (not (and (= (length form) 3) (symbol? (cadr form))))
      (kfm:fail cname "(shadow-field NAME TYPE) expected, got:" (list form)))
  ;; Field annotations must sit BETWEEN the name and ':: type'. In tail
  ;; position Kawa would treat the annotation form as an initializer
  ;; expression, evaluate it to a runtime annotation proxy, and then fail to
  ;; serialize it as a field literal.
  (list (cadr form)
        (kfm:member-annotation cname (list (kfm:at-sym) 'Shadow))
        ':: (caddr form)))

(define (kfm:expand-unique cname form)
  (if (not (and (>= (length form) 2) (pair? (cadr form))))
      (kfm:fail cname "(unique (NAME PARAMS :: RET) body...) expected, got:"
                (list form)))
  (let ((hdr (cadr form)))
    (if (not (and (= (length hdr) 4)
                  (symbol? (car hdr))
                  (eq? (caddr hdr) '::)))
        (kfm:fail cname "unique header must be (NAME PARAMS :: RET), got:"
                  (list hdr)))
    (let ((mname (car hdr))
          (params (cadr hdr))
          (ret (cadddr hdr))
          (body (cddr form)))
      (kfm:check-params cname mname params)
      (cons (cons mname params)
            (cons ':: (cons ret
              (cons (kfm:member-annotation cname (list (kfm:at-sym) 'Unique))
                    body)))))))

(define (kfm:expand cname clauses)
  (let loop ((cs clauses)
             (targets '())
             (extras '())
             (members '()))
    (if (pair? cs)
        (let ((c (car cs)))
          (if (not (and (pair? c) (symbol? (car c))))
              (kfm:fail cname "clause must be a list starting with a symbol, got:"
                        (list c)))
          (cond
           ((memq (car c) '(target targets))
            (for-each (lambda (t)
                        (if (not (string? t))
                            (kfm:fail cname "(target ...) takes strings, got:"
                                      (list t))))
                      (cdr c))
            (loop (cdr cs) (append targets (cdr c)) extras members))
           ((eq? (car c) 'inject)
            (loop (cdr cs) targets extras
                  (append members (list (kfm:expand-inject cname c)))))
           ((eq? (car c) 'shadow-field)
            (loop (cdr cs) targets extras
                  (append members (list (kfm:expand-shadow-field cname c)))))
           ((eq? (car c) 'unique)
            (loop (cdr cs) targets extras
                  (append members (list (kfm:expand-unique cname c)))))
           (else
            (loop (cdr cs) targets (append extras (list c)) members))))
        (begin
          (if (null? targets)
              (kfm:fail cname "at least one (target \"...\") clause is required" '()))
          (let ((mixin-payload
                 (kfm:ser cname
                          (cons (kfm:at-sym)
                                (cons 'Mixin
                                      (cons (cons 'targets targets) extras))))))
            (cons 'define-simple-class
                  (cons cname
                        (cons '()
                              (cons (list (kfm:class-carrier) (kfm:value-kw) mixin-payload)
                                    members)))))))))

;; The transformer returns a raw datum (defmacro-style, non-hygienic), which
;; Kawa accepts. Wrapping the expansion with datum->syntax makes the annotation
;; keyword arrive inside syntax wrappers that define-simple-class then tries to
;; emit as a literal, which fails serialization.
(define-syntax define-mixin
  (lambda (stx)
    (syntax-case stx ()
      ((_ name clause ...)
       (kfm:expand (syntax->datum (syntax name))
                   (syntax->datum (syntax (clause ...))))))))
