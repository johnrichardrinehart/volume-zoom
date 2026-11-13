{
  writeShellApplication,
  coreutils,
  android,
  volume-zoom,
  sign,
}:
writeShellApplication {
  name = "volume-zoom-install";
  runtimeInputs = [
    coreutils
    android.platformTools
    sign
  ];
  runtimeEnv.VOLUME_ZOOM_UNSIGNED_APK = "${volume-zoom}/volume-zoom-unsigned.apk";
  text = builtins.readFile ../../scripts/install.sh;
}
