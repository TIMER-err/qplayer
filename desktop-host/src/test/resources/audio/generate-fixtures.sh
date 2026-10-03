#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

# FFmpeg and Python 3 are needed only to regenerate these synthetic fixtures.
ffmpeg_bin="${FFMPEG:-ffmpeg}"
mono='0.2*sin(2*PI*if(lt(t,0.8),440,if(lt(t,1.6),880,1320))*t)'
stereo="${mono}|0.2*sin(2*PI*if(lt(t,0.8),880,if(lt(t,1.6),1760,2640))*t)"
common=(-hide_banner -loglevel error -y -fflags +bitexact)

# Ordinary M4A with its sample index (moov) after the media (mdat).
"$ffmpeg_bin" "${common[@]}" -f lavfi -i "aevalsrc='${mono}':s=44100:d=2.4" \
  -c:a aac -profile:a aac_low -b:a 48k -flags:a +bitexact -map_metadata -1 mono-tail.m4a

# Ordinary MP4 with a front-loaded index, allowing progressive HTTP playback.
"$ffmpeg_bin" "${common[@]}" -f lavfi -i "aevalsrc='${stereo}':s=48000:d=2.4" \
  -c:a aac -profile:a aac_low -b:a 96k -flags:a +bitexact -map_metadata -1 \
  -movflags +faststart stereo-faststart.mp4

# Single-file DASH representation with an initialization segment and five fragments.
"$ffmpeg_bin" "${common[@]}" -f lavfi -i "aevalsrc='${stereo}':s=44100:d=2.4" \
  -c:a aac -profile:a aac_low -b:a 96k -flags:a +bitexact -map_metadata -1 \
  -movflags +empty_moov+default_base_moof+dash -frag_duration 500000 -f mp4 stereo-dash.m4s

# Video comes first in the track table, so selection must locate the AAC track.
"$ffmpeg_bin" "${common[@]}" -f lavfi -i 'color=c=black:s=16x16:r=5:d=2.4' \
  -f lavfi -i "aevalsrc='${stereo}':s=48000:d=2.4" -map 0:v -map 1:a \
  -c:v mpeg4 -q:v 20 -flags:v +bitexact -c:a aac -profile:a aac_low -b:a 96k \
  -flags:a +bitexact -map_metadata -1 -movflags +faststart video-audio.mp4

# Two tracks in each fragment, with explicit tfhd base-data-offset fields.
"$ffmpeg_bin" "${common[@]}" -f lavfi -i 'color=c=black:s=16x16:r=5:d=2.4' \
  -f lavfi -i "aevalsrc='${stereo}':s=48000:d=2.4" -map 0:v -map 1:a \
  -c:v mpeg4 -q:v 20 -flags:v +bitexact -c:a aac -profile:a aac_low -b:a 96k \
  -flags:a +bitexact -map_metadata -1 -movflags +empty_moov+frag_keyframe \
  -frag_duration 500000 video-audio-fragmented.mp4

# Expand stco to co64 in the trailing moov; preceding media offsets stay valid.
python3 - <<'PY'
from pathlib import Path
from struct import pack, unpack_from

containers = {b'moov', b'trak', b'mdia', b'minf', b'stbl'}
converted = 0

def convert(data):
    global converted
    result = bytearray()
    offset = 0
    while offset < len(data):
        size, kind = unpack_from('>I4s', data, offset)
        assert size >= 8 and offset + size <= len(data)
        body = data[offset + 8:offset + size]
        if kind in containers:
            body = convert(body)
        elif kind == b'stco':
            count = unpack_from('>I', body, 4)[0]
            body = body[:8] + b''.join(pack('>Q', unpack_from('>I', body, 8 + i * 4)[0])
                                      for i in range(count))
            kind = b'co64'
            converted += 1
        result.extend(pack('>I4s', len(body) + 8, kind) + body)
        offset += size
    return result

source = Path('mono-tail.m4a').read_bytes()
assert source.index(b'mdat') < source.index(b'moov')
Path('mono-co64.m4a').write_bytes(convert(source))
assert converted == 1
PY
