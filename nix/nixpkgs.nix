# The root flake and the development partition share this nixpkgs instance.
# It accepts the Android SDK license. All androidenv packages and their source
# archives share one meta attribute set, so its homepage identifies them; no
# other unfree package is allowed.
{ nixpkgs, system }:
import nixpkgs {
  inherit system;
  config = {
    android_sdk.accept_license = true;
    allowUnfreePredicate = pkg: (pkg.meta.homepage or null) == "https://developer.android.com/tools";
  };
}
