#!/usr/bin/env python3
# Folia-compatible Beat This ONNX runner for MuoniumPlayer Folia mode.
# The mel front-end and peak post-processing mirror the Folia reference contract.
import json
import sys

import numpy as np
import onnxruntime as ort

SR = 22050
N_FFT = 1024
HOP = 441
N_MELS = 128
F_MIN = 30.0
F_MAX = 11000.0
LOG_MULTIPLIER = 1000.0
AMIN = 1e-10
CHUNK = 1500
BORDER = 6


def hz_to_mel(hz):
    f_sp = 200.0 / 3.0
    if hz < 1000.0:
        return hz / f_sp
    return 1000.0 / f_sp + np.log(hz / 1000.0) / (np.log(6.4) / 27.0)


def mel_to_hz(mel):
    f_sp = 200.0 / 3.0
    min_log_mel = 1000.0 / f_sp
    if mel < min_log_mel:
        return f_sp * mel
    return 1000.0 * np.exp((np.log(6.4) / 27.0) * (mel - min_log_mel))


def filterbank():
    bins = N_FFT // 2 + 1
    mel_min = hz_to_mel(F_MIN)
    mel_max = hz_to_mel(F_MAX)
    corners = np.array([
        mel_to_hz(mel_min + (mel_max - mel_min) * i / (N_MELS + 1))
        for i in range(N_MELS + 2)
    ], dtype=np.float64)
    freqs = np.arange(bins, dtype=np.float64) * SR / N_FFT
    fb = np.zeros((N_MELS, bins), dtype=np.float64)
    for band in range(N_MELS):
        left, centre, right = corners[band], corners[band + 1], corners[band + 2]
        up = (freqs - left) / (centre - left)
        down = (right - freqs) / (right - centre)
        fb[band] = np.maximum(0.0, np.minimum(up, down))
    return fb


def spectrogram(mono):
    """The checkpoint's log-mel contract: reflected edges, amplitude spectrum."""
    mono = np.asarray(mono, dtype=np.float32)
    pad = N_FFT // 2
    x = np.pad(mono, (pad, pad), mode='reflect')
    frames = max(0, (len(x) - N_FFT) // HOP + 1)
    if frames <= 0:
        return np.zeros((0, N_MELS), dtype=np.float32)
    window = 0.5 - 0.5 * np.cos(2.0 * np.pi * np.arange(N_FFT) / N_FFT)
    fb = filterbank()
    scale = 1.0 / np.sqrt(N_FFT)
    out = np.empty((frames, N_MELS), dtype=np.float32)
    for frame in range(frames):
        start = frame * HOP
        spectrum = np.fft.rfft(x[start:start + N_FFT] * window)
        magnitude = np.abs(spectrum) * scale
        # torchaudio's MelSpectrogram in amplitude mode is the reference contract here.
        energy = np.dot(fb, magnitude)
        out[frame] = np.log1p(LOG_MULTIPLIER * np.maximum(energy, AMIN))
    return out


def chunks(mel):
    frames = mel.shape[0]
    stride = CHUNK - 2 * BORDER
    starts = list(range(-BORDER, frames - BORDER, stride)) or [-BORDER]
    if frames > stride:
        starts[-1] = frames - (CHUNK - BORDER)
    result = []
    for start in starts:
        data = np.zeros((CHUNK, N_MELS), dtype=np.float32)
        source_from = max(0, start)
        source_to = min(start + CHUNK, frames)
        left = max(0, -start)
        right = min(BORDER, start + CHUNK - frames)
        count = left + max(0, source_to - source_from) + right
        if source_to > source_from:
            data[left:left + source_to - source_from] = mel[source_from:source_to]
        result.append((start, data[:count]))
    return result


def deduplicate(peaks, width):
    if not peaks:
        return []
    merged = []
    mean = float(peaks[0])
    count = 1
    for peak in peaks[1:]:
        if peak - mean <= width:
            count += 1
            mean += (peak - mean) / count
        else:
            merged.append(round(mean))
            mean = float(peak)
            count = 1
    merged.append(round(mean))
    return merged


def pick_peaks(logits):
    """Beat This!'s positive seven-frame local maxima, then one-frame deduplication."""
    peaks = []
    for index, raw_value in enumerate(logits):
        value = float(raw_value)
        if not value > 0.0:
            continue
        lo, hi = max(0, index - 3), min(len(logits), index + 4)
        if np.any(logits[lo:hi] > value):
            continue
        peaks.append(index)
    return deduplicate(peaks, 1)


def main():
    if len(sys.argv) != 4:
        raise SystemExit('usage: folia_beat_this.py input.raw model.onnx output.json')
    input_path, model_path, output_path = sys.argv[1:4]
    mono = np.fromfile(input_path, dtype=np.float32)
    mel = spectrogram(mono)
    if mel.shape[0] <= 0:
        raise RuntimeError('input is too short for Beat This')

    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    session = ort.InferenceSession(
        model_path, sess_options=options, providers=['CPUExecutionProvider'])
    input_name = session.get_inputs()[0].name
    outputs = session.get_outputs()
    beat_index, downbeat_index = 0, 1
    for index, output in enumerate(outputs):
        name = output.name.lower()
        if 'beat' in name and 'down' not in name:
            beat_index = index
        elif 'down' in name:
            downbeat_index = index

    predictions = []
    for start, data in chunks(mel):
        count = data.shape[0]
        logits = session.run(None, {input_name: data[None, :, :]})
        b = np.asarray(logits[beat_index]).reshape(-1)
        d = np.asarray(logits[downbeat_index]).reshape(-1)
        predictions.append((start, count, b, d))

    beat_logits = np.full(mel.shape[0], -1000.0, dtype=np.float32)
    downbeat_logits = np.full(mel.shape[0], -1000.0, dtype=np.float32)
    # Reference aggregation is keep-first: earlier chunks win in an overlap.
    for start, count, b, d in reversed(predictions):
        wide = count >= 2 * BORDER
        left = BORDER if wide else 0
        right = count - BORDER if wide else count
        for offset in range(left, right):
            target = start + offset
            if 0 <= target < mel.shape[0]:
                beat_logits[target] = b[offset]
                downbeat_logits[target] = d[offset]

    fps = SR / HOP
    beats = [round(frame / fps, 4) for frame in pick_peaks(beat_logits)]
    downbeats = [round(frame / fps, 4) for frame in pick_peaks(downbeat_logits)]
    if beats and downbeats:
        snapped = [min(beats, key=lambda beat: abs(beat - downbeat)) for downbeat in downbeats]
        downbeats = sorted(set(snapped))
    elif not beats:
        downbeats = []

    with open(output_path, 'w', encoding='utf-8') as output:
        json.dump({'beats': beats, 'downbeats': downbeats}, output, ensure_ascii=False)


if __name__ == '__main__':
    main()
