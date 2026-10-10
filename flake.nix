{
  description = "ketto development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    actrun.url = "github:mizchi/actrun";
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
              actrun.packages.${system}.default
              pkgs.lefthook
            ];
            shellHook = ''
              lefthook install || true
            '';
          };
        });
    };
}
