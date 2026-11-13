# One Android SDK definition for the package build, the sign and install apps,
# and the development shell. The APK build references the build-tools and
# platform packages directly, so its closure excludes the rest of the SDK.
{ pkgs }:
let
  compileSdk = "36";
  buildToolsVersion = "37.0.0";

  composition = pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ compileSdk ];
    buildToolsVersions = [ buildToolsVersion ];
    includeCmake = false;
    toolsVersion = null;
  };

  buildToolsPackage = builtins.head composition.build-tools;
  platformPackage = builtins.head composition.platforms;
  sdk = composition.androidsdk;
in
{
  inherit compileSdk sdk;
  sdkRoot = "${sdk}/libexec/android-sdk";
  # DynamicsProcessing arrived in API 28.
  minSdk = 28;
  targetSdk = 36;
  androidJar = "${platformPackage}/libexec/android-sdk/platforms/android-${compileSdk}/android.jar";
  buildTools = "${buildToolsPackage}/libexec/android-sdk/build-tools/${buildToolsVersion}";
  platformTools = composition.platform-tools;
}
