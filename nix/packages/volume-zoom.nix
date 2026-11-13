# Build an unsigned, aligned APK directly with the SDK tools: aapt2 for
# resources, kotlinc and javac for bytecode, and d8 for DEX. The sign app adds
# the release signature.
{
  lib,
  stdenvNoCC,
  jdk17_headless,
  kotlin,
  zip,
  android,
}:
stdenvNoCC.mkDerivation {
  pname = "volume-zoom";
  # Release tags must be v<version>; bump versionCode with every release.
  version = "0.2.0";
  versionCode = 3;

  src = lib.fileset.toSource {
    root = ../..;
    fileset = lib.fileset.unions [
      ../../app
      ../../core/src
    ];
  };

  nativeBuildInputs = [
    jdk17_headless
    kotlin
    zip
  ];

  buildPhase = ''
    runHook preBuild

    mkdir -p build/gen build/classes build/dex

    ${android.buildTools}/aapt2 compile --dir app/res -o build/resources.zip
    ${android.buildTools}/aapt2 link \
      -I ${android.androidJar} \
      --manifest app/AndroidManifest.xml \
      --min-sdk-version ${toString android.minSdk} \
      --target-sdk-version ${toString android.targetSdk} \
      --version-code "$versionCode" \
      --version-name "$version" \
      --java build/gen \
      -o build/linked.apk \
      build/resources.zip

    find build/gen -name '*.java' -print0 \
      | xargs -0 javac --release 17 -nowarn -d build/classes

    kotlinc \
      -Werror \
      -no-jdk \
      -no-reflect \
      -jvm-target 17 \
      -classpath ${android.androidJar}:build/classes \
      -d build/classes \
      core/src app/src

    jar --create --file build/classes.jar -C build/classes .
    ${android.buildTools}/d8 \
      --release \
      --min-api ${toString android.minSdk} \
      --lib ${android.androidJar} \
      --output build/dex \
      build/classes.jar ${kotlin}/lib/kotlin-stdlib.jar

    cp build/linked.apk build/unaligned.apk
    touch --date=@$SOURCE_DATE_EPOCH build/dex/classes.dex
    (cd build/dex && zip -X -q ../unaligned.apk classes.dex)
    ${android.buildTools}/zipalign -P 16 -f 4 build/unaligned.apk build/volume-zoom-unsigned.apk

    runHook postBuild
  '';

  installPhase = ''
    runHook preInstall
    install -Dm444 build/volume-zoom-unsigned.apk "$out/volume-zoom-unsigned.apk"
    runHook postInstall
  '';

  meta = {
    description = "Unsigned Volume Zoom APK";
    license = lib.licenses.bsd0;
    platforms = [ "x86_64-linux" ];
  };
}
