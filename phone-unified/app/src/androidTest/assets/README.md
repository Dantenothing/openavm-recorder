`synthetic-unified.mp4` is a generated 12-second color-pattern test fixture, not vehicle footage.

Generation command (FFmpeg):

```
ffmpeg -f lavfi -i testsrc2=size=640x360:rate=24 -t 12 -c:v libx264 -preset fast -crf 30 -pix_fmt yuv420p -movflags +faststart -an synthetic-unified.mp4
```

It is packaged in the instrumentation APK only. The production APK contains no test video.
