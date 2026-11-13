{
  description = "Volume Zoom: quieter-than-minimum media volume, driven by the hardware volume keys";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

    flake-parts = {
      url = "github:hercules-ci/flake-parts";
      inputs.nixpkgs-lib.follows = "nixpkgs";
    };
  };

  outputs =
    inputs@{ flake-parts, ... }:
    flake-parts.lib.mkFlake { inherit inputs; } {
      # Google publishes the Android SDK build tools for x86_64 Linux only.
      systems = [ "x86_64-linux" ];

      imports = [ inputs.flake-parts.flakeModules.partitions ];

      perSystem =
        {
          android,
          config,
          pkgs,
          system,
          ...
        }:
        {
          _module.args = {
            pkgs = import ./nix/nixpkgs.nix {
              inherit (inputs) nixpkgs;
              inherit system;
            };
            android = import ./nix/android.nix { inherit pkgs; };
          };

          packages = {
            default = config.packages.volume-zoom;
            volume-zoom = pkgs.callPackage ./nix/packages/volume-zoom.nix { inherit android; };
            sign = pkgs.callPackage ./nix/packages/sign.nix { inherit android; };
            install = pkgs.callPackage ./nix/packages/install.nix {
              inherit android;
              inherit (config.packages) volume-zoom sign;
            };
          };

          apps = {
            sign = {
              type = "app";
              program = pkgs.lib.getExe config.packages.sign;
              meta.description = "Sign the Volume Zoom APK with a release keystore";
            };
            install = {
              type = "app";
              program = pkgs.lib.getExe config.packages.install;
              meta.description = "Sign Volume Zoom, install it over adb and enable its service";
            };
          };
        };

      # Development tools do not enter the input closure of package consumers.
      partitionedAttrs = {
        checks = "dev";
        devShells = "dev";
        formatter = "dev";
      };

      partitions.dev = {
        extraInputsFlake = ./dev;
        module = import ./nix/flake/dev-partition.nix;
      };
    };
}
