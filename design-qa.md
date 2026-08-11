# Wear Apple-style floating header design QA

## Comparison target

- Source visual truth: `C:\Users\hamhuo\AppData\Local\Temp\codex-clipboard-4b3c99fc-d4e0-42b6-9099-88464dc26506.png`
- Implementation screenshot: `C:\Users\hamhuo\tplanner\wear\build\outputs\tplanner-header-apple-material.png`
- Full-view comparison: `C:\Users\hamhuo\tplanner\wear\build\outputs\apple-header-full-comparison.png`
- Focused comparison: `C:\Users\hamhuo\tplanner\wear\build\outputs\apple-header-focus-comparison.png`
- Implementation viewport: Samsung SM-R870, 450 x 450 px, physical density 340 dpi.
- State: inbox main screen after an upward swipe, with the large list title fully off-screen and the compact time/list title visible over the scrolling first card.
- Source dimensions: 222 x 299 px, including Apple Watch hardware and transparent/checkerboard surroundings.
- Implementation dimensions: native 450 x 450 px device screenshot.
- Density normalization: the focused source screen region at x=4, y=36, 214 x 118 px was resized to 450 x 248 px; the matching implementation header was cropped at 450 x 248 px. Hardware geometry, language, and task content are intentionally not fidelity targets.

## Full-view comparison evidence

The reference uses a visually continuous list beneath a sharp, right-aligned white clock and yellow compact title. The first card remains visible through a restrained dark material treatment; there is no independent rectangular header panel. The implementation now follows the same hierarchy: the first task card scrolls behind the clock/title, its text and card surface remain softly recognizable through the material, and the transition into the next sharp card has no visible horizontal seam.

The Apple source is a rounded-square device while the implementation is a round Wear OS device. The implementation retains the existing round-safe right inset and the requested yellow/black product styling rather than copying unsafe Apple edge placement.

## Focused-region comparison evidence

The 900 x 248 px focused comparison places both normalized top regions in one image. It confirms that the compact text stays crisp while the card below is softened, the blur is no longer a uniform gray block, and opacity falls gradually across the full 54 dp header instead of disappearing in the final 12 dp. The material reaches zero alpha before the clipped bottom edge, so the clip is not perceptible.

## Required fidelity surfaces

- Fonts and typography: both references use a compact white time over a yellow list name. Android system typography is retained for platform consistency; size, weight, contrast, truncation, and hierarchy remain readable on the round screen.
- Spacing and layout rhythm: the compact layer remains 54 dp high. The first card continues beneath it, while time and title stay in their established round-safe positions. Existing card density and the floating create control are deliberately unchanged because this iteration targets the header material only.
- Colors and visual tokens: the previous flat `0x48` black tint was replaced by a `0x24 -> 0x16 -> transparent` vertical tint. Blur radius was reduced from 14 dp to 7 dp, and the whole header now uses a multi-stop alpha falloff.
- Image quality and asset fidelity: no raster asset, logo, or icon was replaced. The effect uses the actual scrolling content as its backdrop, not a placeholder or fabricated image. Sharp text is composited above the native blur.
- Copy and content: Chinese inbox/task content remains product-specific. The reference English Notes copy is used only to match visual behavior, not copied into the app.

## Comparison history

1. Earlier P2 finding: the 14 dp blur, uniform `0x48` black overlay, and 12 dp bottom feather produced a recognizable smoky rectangle with a comparatively abrupt lower boundary.
2. Fix: reduced blur to 7 dp; changed the uniform overlay to a three-stop vertical tint; expanded the `DST_IN` mask across the full 54 dp header with alpha stops at 0%, 35%, 72%, and 100%; removed redundant outline clipping; preserved the 16 dp sampling overscan.
3. Robustness fix: on API 26-30, a RenderScript failure now falls back to the live unblurred content plus the light gradient instead of leaving an isolated dark rectangle.
4. Post-fix evidence: `tplanner-header-apple-material.png` and `apple-header-focus-comparison.png` show the first task card remaining visible behind the floating labels, with no identifiable material boundary.

## Findings

No actionable P0, P1, or P2 mismatch remains for the requested Apple-style floating-header material effect.

## Interaction and implementation checks

- An upward swipe collapses the large title only after it leaves the viewport.
- The compact time and inbox title remain fixed, sharp, and readable.
- Task cards continue scrolling beneath the header; the create control remains unchanged.
- `:wear:assembleDebug` and `:wear:lintDebug` completed successfully.
- The updated APK was installed and the final evidence was captured on the physical 450 x 450 px watch.

## Follow-up polish

The Apple reference can place text closer to the edge because it is rounded-square. Moving the Wear clock equally far right would violate the round display's safe area, so the current inset is an intentional platform adaptation rather than an outstanding defect.

final result: passed
