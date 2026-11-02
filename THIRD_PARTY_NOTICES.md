# Third-party model notices

## Real-ESRGAN General x4v3 LiteRT

Sruvik includes the FP16 LiteRT-compatible export from LiteRT Community, based on the Real-ESRGAN General x4v3 weights.

- Model repository: https://huggingface.co/litert-community/real-esrgan-x4v3-litert
- Upstream weights and implementation: https://huggingface.co/qualcomm/Real-ESRGAN-General-x4v3
- Model license: BSD-3-Clause
- Pinned model revision: 1017caa
- SHA-256: 6d597b46d812ada42bdde641ec6e78c0ef0b78ff5a825fcfd74b9a1b3bcbe9a4

See the upstream model repositories and license files for full notices.


## Nezumi Kiln MNN native runtime (arm64 Android)

Sruvik integrates the Nezumi Kiln MNN Stable Diffusion runtime and its JNI bridge from the following pinned public source revision:

- Source: https://github.com/mouse0329/nezumi-ai
- Pinned revision: 78ea304dd1f0b2082d73f33b1283948c2fdd8e21
- License: LGPL-3.0; the upstream project also offers a separate commercial license. Sruvik uses the LGPL-3.0 source distribution. See `third_party/nezumi-ai/LICENSE.md`, `third_party/nezumi-ai/LGPL_LICENSE`, and `third_party/nezumi-ai/NOTICE`.
- The three arm64 Android shared libraries are staged from that exact submodule revision and their Git blob hashes are checked by CI before packaging. They are not copied from an unpinned binary mirror.

The DreamShaper 8 MNN model pack is a separate download and is not bundled by this runtime integration. It is based on Lykon's DreamShaper 8 and is distributed under CreativeML Open RAIL-M; model-specific terms and attribution apply separately. It remains disabled in Sruvik until its exact archive integrity hash is pinned and device validation is completed.

See the pinned source and model cards for full notices and restrictions.
