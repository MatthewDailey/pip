`rive.js` and `rive.wasm` from `@rive-app/canvas` 2.52.0 (npm, MIT), copied unmodified so the
previewer and goldens work offline with a fixed runtime. `package.json` is the package's own, kept
for provenance. To bump: `npm pack @rive-app/canvas@<version>`, copy the same two files into a new
`rive-canvas-<version>/` folder, point `RUNTIME` in `preview.js` at it, and re-run
`node golden.mjs --update` after reviewing the diffs.
