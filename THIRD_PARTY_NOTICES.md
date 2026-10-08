# Third-Party Notices

OverDrive's own source code is licensed under the MIT License (see [LICENSE](LICENSE)).
The MIT license applies **only** to OverDrive's own code. The application bundles and
distributes the third-party components listed below, each of which remains under its
own license — those licenses, not MIT, govern those components.

## Bundled native binaries (`app/src/main/jniLibs/`)

| Component | Upstream | Version | License | Modified? |
|-----------|----------|---------|---------|-----------|
| cloudflared | https://github.com/cloudflare/cloudflared | based on 2025.7.0 | Apache-2.0 | **Yes** — see "Modifications" below |
| zrok | https://github.com/openziti/zrok | based on v1.1.x | Apache-2.0 | **Yes** — see "Modifications" below |
| sing-box | https://github.com/SagerNet/sing-box | see upstream | **GPL-3.0-or-later** | No |
| tailscale | https://github.com/tailscale/tailscale | v1.96.4 | BSD-3-Clause | **Yes** — see "Modifications" below |
| wgproxy (`libwireguard.so`) | OverDrive's own [`tools/wireguard`](tools/wireguard), built on [wireguard-go](https://git.zx2c4.com/wireguard-go) | pinned in `tools/wireguard/go.mod` | MIT (OverDrive code and wireguard-go); bundles gVisor netstack (Apache-2.0) and golang.org/x/{crypto,net,sys,time} (BSD-3-Clause) | No — upstream modules are used unmodified |

## Machine-learning models (`app/src/main/assets/models/`)

| Component | Upstream | License |
|-----------|----------|---------|
| YOLO11n (`yolo11n.tflite`) | https://github.com/ultralytics/ultralytics | **AGPL-3.0** |

## Native libraries linked into the app

| Component | Upstream | License |
|-----------|----------|---------|
| OpenCV | https://github.com/opencv/opencv | Apache-2.0 |
| OpenH264 | https://github.com/cisco/openh264 | BSD-2-Clause |
| TensorFlow Lite | https://github.com/tensorflow/tensorflow | Apache-2.0 |

## Modifications (Apache-2.0 §4(b) notice of changes)

The following components were modified from their upstream sources:

- **cloudflared** — added a proxy-aware edge dialer that routes the tunnel's edge
  connection through a SOCKS5/HTTP proxy read from the environment
  (`edgediscovery/dial.go`). This lets the tunnel operate on restricted networks.
- **zrok** — added a SOCKS5 proxy and DNS override path for Android / restricted
  networks, selected via the `ALL_PROXY` / `HTTPS_PROXY` / `HTTP_PROXY` environment
  variables (`cmd/zrok/main.go`).
- **tailscale** — two changes, both needed to run on an Android head unit.
  (1) The local API speaks loopback TCP instead of a unix socket
  (`safesocket/unixsocket.go`, `ipn/ipnauth/*`): tailscaled runs as the shell uid
  here while its clients run as app uids, and no directory is both shell-writable
  and app-readable. (2) The ACME client is given a resolver when the platform
  provides none (`ipn/ipnlocal/cert.go`): Android has no `/etc/resolv.conf`, so a
  cgo-free build cannot resolve the ACME directory and certificate issuance fails.
  Both are kept in `tools/patches/tailscale-tcp-socket.patch` and applied by
  `tools/build-libtailscale.sh`, which also records the build tags and target.

## Source-derived work

- **Bangcle / white-box AES port** — derived from reverse-engineering work by
  [Niek/BYD-re](https://github.com/Niek/BYD-re) and
  [jkaberg/pyBYD](https://github.com/jkaberg/pyBYD).

## Corresponding source (copyleft components)

**sing-box** is licensed under **GPL-3.0-or-later** and **YOLO11n** under **AGPL-3.0**.
Their complete corresponding source (including any modifications, if applicable) is
available from the upstream projects linked above.

**Written offer:** for three years from the date of distribution, the maintainer will,
on request, provide the complete corresponding source code for the GPL-3.0 and AGPL-3.0
components as bundled in any given OverDrive release. Contact the maintainer via the
channels in [SECURITY.md](SECURITY.md) or the [Discord server](https://discord.gg/PZutk9fg4h).

The full text of each license (GPL-3.0, AGPL-3.0, Apache-2.0, BSD-2-Clause,
BSD-3-Clause) is available from the respective upstream repositories.
