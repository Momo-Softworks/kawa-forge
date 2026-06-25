;;; manifest.scm --- Guix dev environment for the Kawa Forge Gradle plugin
;;;
;;; This project is a Gradle plugin — it just needs a JDK to compile and
;;; publish.  No Minecraft, no native libraries.
;;;
;;; Usage:
;;;   guix shell -m manifest.scm
;;;   direnv allow          (automatic via .envrc)

(specifications->manifest
 (list
  ;; --- JVM toolchain ---
  "openjdk@25:jdk"          ;full JDK: java + javac for Gradle

  ;; --- Shell plumbing ---
  "bash"
  "coreutils"
  "which"
  "git"
  "make"

  ;; --- TLS roots + UTF-8 ---
  "nss-certs"
  "glibc-locales"))
