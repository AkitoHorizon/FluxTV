#!/usr/bin/env bash
# Médias de test : film MP4, épisode MKV (2 pistes audio + sous-titres), flux direct MPEG-TS
set -e
mkdir -p media && cd media
ffmpeg -loglevel error -y -f lavfi -i testsrc2=size=1280x720:rate=25 -f lavfi -i sine=frequency=440:sample_rate=48000 \
  -t 120 -c:v libx264 -profile:v main -pix_fmt yuv420p -g 50 -c:a aac -b:a 128k -movflags +faststart test.mp4
printf '1\n00:00:01,000 --> 00:00:20,000\nSous-titre de test : « Bonjour à tous »\n\n2\n00:00:21,000 --> 00:01:20,000\nDeuxième ligne de sous-titres\n' > subs.srt
ffmpeg -loglevel error -y -f lavfi -i testsrc=size=1280x720:rate=25 -f lavfi -i sine=frequency=330:sample_rate=48000 -f lavfi -i sine=frequency=660:sample_rate=48000 -i subs.srt \
  -t 90 -map 0:v -map 1:a -map 2:a -map 3:s -c:v libx264 -pix_fmt yuv420p -g 50 -c:a aac -c:s srt \
  -metadata:s:a:0 language=fre -metadata:s:a:1 language=eng -metadata:s:s:0 language=fre test.mkv
ffmpeg -loglevel error -y -f lavfi -i smptebars=size=1280x720:rate=25 -f lavfi -i sine=frequency=500:sample_rate=48000 \
  -t 180 -c:v libx264 -pix_fmt yuv420p -g 25 -c:a aac -f mpegts test.ts
ls -la
