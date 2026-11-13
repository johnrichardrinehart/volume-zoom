{ inputs, ... }:
{
  imports = [
    inputs.git-hooks.flakeModule
    inputs.treefmt-nix.flakeModule
  ];

  perSystem =
    {
      android,
      config,
      pkgs,
      ...
    }:
    {
      # One definition drives `nix fmt` and the treefmt Git hook. The hook is
      # also exercised by the sandboxed pre-commit check below.
      treefmt = {
        projectRootFile = "flake.nix";

        # `checks.pre-commit` already runs the treefmt hook in the sandbox along
        # with Git-specific hooks. Avoid a second derivation that repeats it.
        flakeCheck = false;
        programs = {
          actionlint.enable = true;
          deadnix.enable = true;
          ktfmt.enable = true;
          nixfmt.enable = true;
          prettier.enable = true;
          ruff-format.enable = true;
          shellcheck.enable = true;
          shfmt.enable = true;
          statix.enable = true;
          xmllint.enable = true;
        };
        settings.formatter = {
          ktfmt.options = [ "--kotlinlang-style" ];
          shellcheck.includes = [
            "*.sh"
            ".envrc"
          ];
          shfmt.includes = [
            "*.sh"
            ".envrc"
          ];
        };
      };

      pre-commit.settings.hooks = {
        treefmt.enable = true;
        check-added-large-files.enable = true;
        check-merge-conflicts.enable = true;

        flake-check-before-push = {
          enable = true;
          name = "authoritative Nix checks";
          entry = "${pkgs.nix}/bin/nix flake check --print-build-logs";
          always_run = true;
          pass_filenames = false;
          require_serial = true;
          stages = [ "pre-push" ];
        };
      };

      checks = {
        inherit (config.packages) volume-zoom;
        core-tests = pkgs.callPackage ../checks/core-tests.nix { };
      };

      devShells.default = pkgs.mkShell {
        packages = [
          android.sdk
          android.platformTools
          pkgs.jdk17_headless
          pkgs.kotlin
          pkgs.kotlin-language-server
          pkgs.nixd
          # tools/measure-tone.py
          (pkgs.python3.withPackages (ps: [ ps.numpy ]))
        ];
        env = {
          ANDROID_HOME = android.sdkRoot;
          ANDROID_SDK_ROOT = android.sdkRoot;
        };
        shellHook = config.pre-commit.installationScript;
      };
    };
}
