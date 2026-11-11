# Design research references

Research date: 2026-11-11

The source collection was shallow-cloned to a temporary directory (outside this application) from [VoltAgent/awesome-design-md](https://github.com/VoltAgent/awesome-design-md), commit `fcc70284932276d085f01ef9f83edde90b1258cb`. Its `design-md/` directory contains 84 brand analyses. The repository identifies itself as a curated collection; its root `LICENSE` is MIT, copyright VoltAgent, 2026. The source files are not copied into this repository. If a future change copies or adapts substantial source text, retain the MIT notice and license; this research document attributes the references and links directly to their files.

## Selected references

| Reference | Source | What informed the product direction | What we are not adopting |
|---|---|---|---|
| IBM Carbon | [design-md/ibm/DESIGN.md](https://github.com/VoltAgent/awesome-design-md/blob/main/design-md/ibm/DESIGN.md) | Strong enterprise hierarchy, clear semantic colors, restrained blue action color, legible dense information, flat bordered surfaces. | IBM branding, exact blue palette, proprietary font assumptions. |
| Sentry | [design-md/sentry/DESIGN.md](https://github.com/VoltAgent/awesome-design-md/blob/main/design-md/sentry/DESIGN.md) | Operational dashboard patterns: high information density, compact data labels, status communication and dark surfaces for long-running monitoring. | Its brand colors and dark-first marketing expression. |
| HashiCorp | [design-md/hashicorp/DESIGN.md](https://github.com/VoltAgent/awesome-design-md/blob/main/design-md/hashicorp/DESIGN.md) | Infrastructure product clarity, neutral surfaces, carefully assigned accents, consistent component treatment. | Product-specific accent colors or black-only visual identity. |
| Linear | [design-md/linear.app/DESIGN.md](https://github.com/VoltAgent/awesome-design-md/blob/main/design-md/linear.app/DESIGN.md) | Compact navigation, precise typographic hierarchy, focused interaction states and consistent spacing. | Minimalism that hides operational detail, or its lavender brand accent. |

## Candidate assessment

Carbon is the best baseline for an enterprise fleet console because its documented palette and component principles favor legibility and explicit semantic roles. Sentry is the best operational reference for alert states and dense monitoring views. HashiCorp contributes useful infrastructure product conventions, while Linear is a useful secondary reference for navigation and interaction polish. The selected set balances enterprise dashboard usability, navigation, tables, status communication, accessibility, responsive behavior, and maintainability. Reference claims are based on the actual source `DESIGN.md` files, not merely the repository's summary descriptions.

The proposed Endpoint Posture system in [`../DESIGN.md`](../DESIGN.md) is an original product direction. It keeps the existing product's graphite/teal cues as a starting point, introduces explicit accessible semantic tokens and dense data-table rules, and does not reproduce any selected brand's identity. It is a planning document, not authorization to implement the frontend.

## License and attribution

The source collection is distributed under the [MIT License](https://github.com/VoltAgent/awesome-design-md/blob/main/LICENSE). This repository stores links and analysis only; it does not contain copied source design files. Preserve the source copyright and license notice if future work copies or adapts source material. The brand names and linked design files are credited to their respective source collection.
