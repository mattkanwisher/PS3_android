# Branding assets

Drop the official artwork here (raster originals — Claude can't extract
pixel data from chat images, so these must be committed directly):

| File | Use | Spec |
|---|---|---|
| `icon.png` | app launcher icon source, store listing | square, ≥1024×1024 |
| `banner.png` | README header, release pages | the "CELLSTATION — PS3 emulator for Android" lockup |

The in-app launcher icon currently uses a vector approximation of the mark
(`app/src/main/res/drawable/ic_launcher_foreground.xml`); once `icon.png`
lands, generate proper mipmaps from it (Android Studio's Image Asset tool, or
`icon.png` scaled into `mipmap-*` densities) and keep the adaptive-icon
background `#0A101C`.
