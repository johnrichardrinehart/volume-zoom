# Compile the platform-independent key-handling state machine with its tests
# for the JVM and run them.
{
  lib,
  stdenvNoCC,
  jdk17_headless,
  kotlin,
}:
stdenvNoCC.mkDerivation {
  name = "volume-zoom-core-tests";

  src = lib.fileset.toSource {
    root = ../..;
    fileset = ../../core;
  };

  nativeBuildInputs = [
    jdk17_headless
    kotlin
  ];

  buildPhase = ''
    runHook preBuild
    kotlinc -Werror -jvm-target 17 \
      -classpath ${kotlin}/lib/kotlin-test.jar \
      -d build core/src core/test
    java -classpath build:${kotlin}/lib/kotlin-stdlib.jar:${kotlin}/lib/kotlin-test.jar \
      dev.johnrinehart.volumezoom.TestMainKt
    runHook postBuild
  '';

  installPhase = ''
    touch "$out"
  '';
}
