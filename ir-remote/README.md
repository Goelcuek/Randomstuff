# TV Remote (IR)

Android IR remote for TVs, for phones with an IR blaster (uses `ConsumerIrManager`).

- 117 brands / ~380 code sets from [Flipper-IRDB](https://github.com/Lucaslhm/Flipper-IRDB) (CC0), pre-encoded to raw pulse timings by `tools/build_codes.py`
- Setup wizard: pick brand → test Power / Vol+ / Mute → step through codes until the TV reacts
- Multiple saved TVs, hold-to-repeat on volume/channel/arrows, numbers, colour keys, media keys

Rebuild the code asset: `python3 tools/build_codes.py <Flipper-IRDB>/TVs app/src/main/assets/tvcodes.json`

APK is built by `.github/workflows/ir-remote.yml` and published as a GitHub release.
