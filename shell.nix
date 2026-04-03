let
  pkgs = import ./nixpkgs.nix;
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
  robolectricSdk28Jar = pkgs.fetchurl {
    url = "https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented/9-robolectric-4913185-2-i4/android-all-instrumented-9-robolectric-4913185-2-i4.jar";
    hash = "sha256-UMCiYrggjCgqWAU83RSnfoqgqoft2rLuT4RnOGXa+eM=";
  };
  robolectricSdk34Jar = pkgs.fetchurl {
    url = "https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented/14-robolectric-10818077-i4/android-all-instrumented-14-robolectric-10818077-i4.jar";
    hash = "sha256-o2O7AQo+geXEW5NwILOV9NsPsA1bxqpEx7WvxNFcnIk=";
  };
  robolectricDepsProperties = pkgs.writeText "robolectric-deps.properties" ''
    org.robolectric\:android-all-instrumented\:9-robolectric-4913185-2-i4=${robolectricSdk28Jar}
    org.robolectric\:android-all-instrumented\:14-robolectric-10818077-i4=${robolectricSdk34Jar}
  '';
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
    export SIGNING_STORE_FILE=$PWD/signing/app.jks
    export SIGNING_STORE_PASSWORD=changeme
    export SIGNING_KEY_ALIAS=app
    export SIGNING_KEY_PASSWORD=changeme
    export ROBOLECTRIC_DEPS_PROPERTIES=${robolectricDepsProperties}
  '';
}
