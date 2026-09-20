# D3400 Bulb Remote

A deliberately simple Android USB/PTP remote for the Nikon D3400.

## Design

The app does not meter, autofocus, choose shutter speed, or configure Bulb mode.

It behaves like a timed remote:

1. Send Nikon START capture command with **no autofocus**.
2. Count the requested exposure time on the Android phone.
3. Send Nikon STOP/TerminateCapture.
4. Wait for the camera to finish writing.
5. Repeat after the selected pause.

## Camera setup

Before pressing START:

- Mode dial: **M**
- Shutter speed: **Bulb**
- Focus: **Manual focus recommended**
- Memory card inserted
- Connect Android phone using USB OTG

The app uses Nikon PTP vendor commands:
- DeviceReady: 0x90C8
- InitiateCaptureRecInMedia: 0x9207
- TerminateCapture: 0x920C

For capture start it uses Nikon's **0xFFFFFFFF = no AF before capture** parameter.

## Why this version exists

Earlier versions tried to configure camera properties and had fallback capture paths. This version intentionally removes those. A dark subject should not matter because the phone only tells the already-configured camera when to start and stop the Bulb exposure.
