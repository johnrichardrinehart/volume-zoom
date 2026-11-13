{
  writeShellApplication,
  coreutils,
  jdk17_headless,
  android,
}:
writeShellApplication {
  name = "volume-zoom-sign";
  runtimeInputs = [
    coreutils
    jdk17_headless
  ];
  runtimeEnv.APKSIGNER = "${android.buildTools}/apksigner";
  text = builtins.readFile ../../scripts/sign.sh;
}
