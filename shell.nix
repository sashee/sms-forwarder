let
  pinnedNixpkgs = builtins.fetchTarball "https://github.com/NixOS/nixpkgs/archive/refs/heads/nixos-25.11.tar.gz";
  pkgs = import pinnedNixpkgs {
    config = {
      allowUnfree = true;
      android_sdk.accept_license = true;
    };
  };
in

let
  android = pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ "34" ];
    buildToolsVersions = [ "34.0.0" ];
    abiVersions = [ "x86_64" "arm64-v8a" ];
    includeEmulator = false;
    includeNDK = false;
  };
  androidSdk = android.androidsdk;
in
pkgs.mkShell {
  packages = [
    pkgs.gradle
    pkgs.jdk17
    androidSdk
  ];

  shellHook = ''
    export JAVA_HOME=${pkgs.jdk17}
    export ANDROID_SDK_ROOT=${androidSdk}/libexec/android-sdk
    export ANDROID_HOME=$ANDROID_SDK_ROOT
    export CACERT_PEM=${pkgs.cacert}/etc/ssl/certs/ca-bundle.crt
  '';
}
