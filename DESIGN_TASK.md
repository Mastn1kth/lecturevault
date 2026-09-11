# Interface release work

User request: «нет сделай везде топовый интерефейс типо и на мак айфон и виндовс и андроид».

- [x] Android: redesigned recording, library, settings, first-run and real states.
- [x] Windows/macOS: adaptive desktop UI, real library, styled settings and rendered verification.
- [x] iPhone/iPad: cohesive SwiftUI source, lifecycle/accessibility and source validation.
- [x] Verify builds and package distinctly versioned artifacts; document device-only verification gaps.
- [x] Check Google Play release blockers against actual package and official requirements.

Visual direction: dark recording studio, warm orange, clear editorial hierarchy. Existing Android is
the product reference, but the desktop adapts to a wide window. No fake history or simulated live levels
in production. Native pickers keep OS behavior. Shared colors: background #0D0F12, surface #171A20,
elevated #20242B, text #F3F1EC, muted #AAB0BC, divider #2A2F38, accent #FF7849, success #69D8A0.
