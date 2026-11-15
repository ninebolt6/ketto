{
  description = "ketto development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    actrun = {
      url = "https://github.com/mizchi/actrun/releases/download/v0.32.0/mizchi-actrun-0.32.0.tgz";
      flake = false;
    };
  };

  outputs = { nixpkgs, actrun, ... }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "aarch64-darwin" ];
      forAllSystems = nixpkgs.lib.genAttrs systems;
    in
    {
      devShells = forAllSystems (system:
        let pkgs = nixpkgs.legacyPackages.${system};
        in {
          default = pkgs.mkShell {
            packages = [
              pkgs.temurin-bin-25
              (pkgs.writeShellApplication {
                name = "actrun";
                runtimeInputs = [ pkgs.nodejs pkgs.git ];
                text = ''
                  exec node ${actrun}/dist/actrun.js "$@"
                '';
              })
              pkgs.lefthook
            ];
            shellHook = ''
              lefthook install || true
            '';
          };
        });
    };
}
