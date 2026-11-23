# Known block screens

Drop a screenshot (`.jpg`, `.png`, `.webp` or `.bmp`) of any "go away, scraper" screen, captcha
picture or fake channel here. The Live TV soak test (`provider-engine/tools/soak.mjs`) saves a frame
every 20 seconds and fails the stream if one looks like a picture in this folder.

- A screenshot of the full picture works best. Crop off any black bars added by your TV or phone.
- Name the file after what it is, for example `freeshot-scraper-blocked.jpg`: the name appears in the
  failure reason.
- Nearly blank pictures (all black, all one colour) are ignored, because they prove nothing.

Automatic fixes may not add, change or remove files here.
