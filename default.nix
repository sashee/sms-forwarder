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
  buildGradleApplicationSrc = builtins.fetchTarball {
    url = "https://github.com/raphiz/buildGradleApplication/archive/refs/heads/main.tar.gz";
    sha256 = "0rkw2i0bk3dgmkgsqrcxsg0092shcf0s8q7dvxvw3niklxri6lrp";
  };
  fetchArtifact = pkgs.callPackage "${buildGradleApplicationSrc}/fetchArtefact/default.nix" {};
  mkM2Repository = pkgs.callPackage "${buildGradleApplicationSrc}/buildGradleApplication/mkM2Repository.nix" {
    inherit fetchArtifact;
  };
  android = pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ "34" ];
    buildToolsVersions = [ "34.0.0" ];
    abiVersions = [ "x86_64" "arm64-v8a" ];
    includeEmulator = false;
    includeNDK = false;
  };
  androidSdk = android.androidsdk;
  buildTools = "${androidSdk}/libexec/android-sdk/build-tools/34.0.0";
  # Robolectric resolves Android runtime jars outside normal Gradle dependency resolution,
  # so these jars and the generated robolectric-deps.properties mapping must be provided
  # explicitly instead of relying on verification-metadata.xml or the offline Maven repo.
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
  offlineRepository = mkM2Repository {
    pname = "sms-forwarder";
    version = "0.1.0";
    src = ./.;
    repositories = [
      "https://dl.google.com/dl/android/maven2"
      "https://repo1.maven.org/maven2"
      "https://plugins.gradle.org/m2"
    ];
  };
in
pkgs.stdenv.mkDerivation {
  pname = "sms-forwarder";
  version = "0.1.0";
  src = ./.;
  preferLocalBuild = true;
  allowSubstitutes = false;

  nativeBuildInputs = [
    pkgs.gradle
    pkgs.jdk17
    androidSdk
  ];

  buildPhase = ''
    export JAVA_HOME=${pkgs.jdk17}
    export ANDROID_SDK_ROOT=${androidSdk}/libexec/android-sdk
    export ANDROID_HOME=$ANDROID_SDK_ROOT
    export CACERT_PEM=${pkgs.cacert}/etc/ssl/certs/ca-bundle.crt
    export MAVEN_SOURCE_REPOSITORY=${offlineRepository.m2Repository}
    export ROBOLECTRIC_DEPS_PROPERTIES=${robolectricDepsProperties}
    mkdir -p .gradle-home
    export GRADLE_USER_HOME=$PWD/.gradle-home
    gradle --offline --no-daemon --init-script ${./nix/offline-init.gradle.kts} -Dorg.gradle.project.android.aapt2FromMavenOverride=${buildTools}/aapt2 testDebugUnitTest assembleDebug
  '';

  installPhase = ''
    mkdir -p $out
    cp app/build/outputs/apk/debug/app-debug.apk $out/
  '';
}
