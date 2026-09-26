// Regenerates the colour roles in ui/design KftColors.kt from the KFT navy seed (#1F2E5D). Not part of the build.
// Run (Node 22):  npm i @material/material-color-utilities@0.4.0 esbuild
//                npx esbuild generate_scheme.mjs --bundle --platform=node --outfile=gen.cjs && node gen.cjs
// (0.4.0's ESM build has extension-less imports Node can't load directly, hence the esbuild bundle.)
// The hand changes applied after generating are listed in the KftColors KDoc.
import { argbFromHex, hexFromArgb, Hct, SchemeTonalSpot, DynamicScheme, TonalPalette, MaterialDynamicColors as M } from '@material/material-color-utilities';
const seedArgb = argbFromHex('#1F2E5D'); const seed = Hct.fromInt(seedArgb);
const roles = ['primary','onPrimary','primaryContainer','onPrimaryContainer','inversePrimary','secondary','onSecondary','secondaryContainer','onSecondaryContainer','background','onBackground','surface','onSurface','surfaceVariant','onSurfaceVariant','inverseSurface','inverseOnSurface','error','onError','errorContainer','onErrorContainer','outline','outlineVariant','scrim','surfaceBright','surfaceDim','surfaceContainer','surfaceContainerHigh','surfaceContainerHighest','surfaceContainerLow','surfaceContainerLowest'];
function emit(name, s) {
  console.log(`// ${name}`); console.log(roles.map(r => `${r} = Color(0xFF${hexFromArgb(M[r].getArgb(s)).slice(1).toUpperCase()}),`).join('\n'));
}
const base = (dark, c) => new SchemeTonalSpot(seed, dark, c);
emit('light', base(false, 0));
emit('hclight', base(false, 1));
// Dark: same palettes, but navy-tinted neutrals (chroma 12 / 16 instead of TonalSpot's 6 / 8).
const t = base(true, 0);
const dark = new DynamicScheme({ sourceColorHct: seed, variant: t.variant, contrastLevel: 0, isDark: true,
  primaryPalette: t.primaryPalette, secondaryPalette: t.secondaryPalette, tertiaryPalette: t.secondaryPalette,
  neutralPalette: TonalPalette.fromHueAndChroma(seed.hue, 12), neutralVariantPalette: TonalPalette.fromHueAndChroma(seed.hue, 16) });
emit('dark navy-tinted', dark);
