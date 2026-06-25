# Kawa Forge Gradle Plugin — common tasks
#
#   make          build + publish to ~/.m2
#   make publish  publish to mavenLocal
#   make clean    remove build artifacts

.PHONY: all publish clean

all: publish

publish:
	./gradlew publishToMavenLocal

clean:
	rm -rf build .gradle .direnv
