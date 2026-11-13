{
  description = "Volume Zoom: sub-floor media attenuation driven by the hardware volume keys";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs =
    { nixpkgs, ... }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };
      buildToolsVersion = "36.0.0";
      android = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "36" ];
        buildToolsVersions = [ buildToolsVersion ];
        includeEmulator = false;
        includeNDK = false;
      };
      sdk = android.androidsdk;
      sdkRoot = "${sdk}/libexec/android-sdk";
    in
    {
      devShells.${system}.default = pkgs.mkShell {
        packages = [
          sdk
          pkgs.jdk17
          pkgs.gradle
          pkgs.android-tools
        ];
        ANDROID_HOME = sdkRoot;
        ANDROID_SDK_ROOT = sdkRoot;
        JAVA_HOME = pkgs.jdk17.home;
        # AGP downloads a dynamically linked aapt2 from Maven that cannot run on NixOS.
        GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdkRoot}/build-tools/${buildToolsVersion}/aapt2";
      };

      formatter.${system} = pkgs.nixfmt-rfc-style;
    };
}
