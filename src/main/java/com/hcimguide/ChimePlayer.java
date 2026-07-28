package com.hcimguide;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.audio.AudioPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Short confirmation chimes played through RuneLite's own
 * {@link AudioPlayer} rather than the game's sound engine.
 *
 * <p>{@code Client.playSoundEffect} hands the sound to the game engine, so it
 * follows the in-game sound sliders. Players who mute the game lose the
 * plugin's confirmations with it. Going through the client's audio player
 * keeps them audible with the game silent, and gives them a volume of their
 * own.</p>
 *
 * <p>The tones are generated as raw samples and wrapped in an in-memory WAV
 * header rather than shipped as audio files: nothing to license, nothing
 * binary in the repository, and the pitch and length can be tuned by editing
 * numbers.</p>
 *
 * <p>Playback is best effort. A machine with no audio device, or a mixer that
 * refuses the format, simply produces no sound and never an error the player
 * has to care about.</p>
 */
@Singleton
public class ChimePlayer
{
	private static final Logger log = LoggerFactory.getLogger(ChimePlayer.class);

	private static final float SAMPLE_RATE = 44100f;

	/**
	 * Rising major arpeggio: C6, E6, G6. A perfect fifth resolving upward is
	 * what reads as "success" rather than "notification"; the earlier two-note
	 * pair was a bare fourth and sounded like an alert.
	 */
	private static final double[] SUCCESS_HZ = {1046.50, 1318.51, 1567.98};

	/** Same arpeggio carried to the octave, so trip-ready is clearly bigger. */
	private static final double[] READY_HZ = {1046.50, 1318.51, 1567.98, 2093.00};

	private static final int NOTE_MS = 60;

	/** Notes overlap slightly so the run sounds like one gesture, not three beeps. */
	private static final double OVERLAP = 0.35;

	/** Release the one-at-a-time gate this long after the clip's own duration. */
	private static final long CLOSE_MARGIN_MS = 150;

	/**
	 * One chime at a time. Rapid step completions previously spawned a thread
	 * and an audio line each, which overlapped audibly and left plugin-owned
	 * threads for a reviewer to account for.
	 */
	private final java.util.concurrent.atomic.AtomicBoolean playing =
		new java.util.concurrent.atomic.AtomicBoolean();
	private final AudioPlayer audioPlayer;
	private final java.util.concurrent.ScheduledExecutorService executor;

	@Inject
	ChimePlayer(AudioPlayer audioPlayer, java.util.concurrent.ScheduledExecutorService executor)
	{
		this.audioPlayer = audioPlayer;
		this.executor = executor;
	}

	/** @param volumePercent 0 to 100, from the plugin's own setting */
	void playSuccess(int volumePercent)
	{
		play(SUCCESS_HZ, volumePercent);
	}

	void playTripReady(int volumePercent)
	{
		play(READY_HZ, volumePercent);
	}

	private void play(double[] notes, int volumePercent)
	{
		if (volumePercent <= 0 || !playing.compareAndSet(false, true))
		{
			return;
		}
		// Off the caller's thread: opening a clip can block briefly. Use
		// RuneLite's managed executor rather than creating a plugin-owned thread
		// for every accepted chime.
		try
		{
			executor.execute(() -> startTone(notes, volumePercent));
		}
		catch (RuntimeException e)
		{
			// A shutdown-time executor rejection must not leave playback locked.
			playing.set(false);
			log.debug("Chime scheduling unavailable", e);
		}
	}

	private void startTone(double[] notes, int volumePercent)
	{
		byte[] pcm = render(notes, Math.min(100, volumePercent) / 100.0);
		try
		{
			// AudioPlayer buffers the whole in-memory clip into a self-closing
			// line and returns as soon as playback starts, so this task never
			// parks the SHARED executor for the clip's audible duration.
			audioPlayer.play(new ByteArrayInputStream(toWav(pcm)), gainDb(volumePercent));
			// keep the one-at-a-time gate up until the clip has audibly
			// finished, then release it via a scheduled task
			long clipMillis = (long) Math.ceil(pcm.length * 1000.0 / (SAMPLE_RATE * 2));
			executor.schedule(() -> playing.set(false),
				clipMillis + CLOSE_MARGIN_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
		}
		catch (Exception e)
		{
			// no audio device, or the mixer refused the format. Silence is an
			// acceptable outcome for a confirmation chime.
			log.debug("Chime playback unavailable", e);
			playing.set(false);
		}
	}

	/**
	 * The samples are already scaled by the volume fraction; some mixers
	 * ignore per-sample amplitude, so also ask for the equivalent line gain.
	 * Clamped well inside every common mixer's control range so the client's
	 * gain helper never has to reject the value.
	 */
	private static float gainDb(int volumePercent)
	{
		float fraction = Math.max(0.0001f, Math.min(1f, volumePercent / 100f));
		// decibels, not a linear scale; 100% keeps the line's natural level
		return Math.max(-40f, (float) (20.0 * Math.log10(fraction)));
	}

	/**
	 * Wrap 16-bit little-endian mono PCM in a canonical 44-byte RIFF/WAVE
	 * header, entirely in memory, so the client's audio player can parse it
	 * like any WAV resource - without this plugin touching {@code javax.sound}.
	 */
	private static byte[] toWav(byte[] pcm)
	{
		ByteBuffer buffer = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
		int sampleRate = (int) SAMPLE_RATE;
		buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII));
		buffer.putInt(36 + pcm.length);
		buffer.put("WAVE".getBytes(StandardCharsets.US_ASCII));
		buffer.put("fmt ".getBytes(StandardCharsets.US_ASCII));
		buffer.putInt(16);                 // PCM format chunk size
		buffer.putShort((short) 1);        // uncompressed PCM
		buffer.putShort((short) 1);        // mono
		buffer.putInt(sampleRate);
		buffer.putInt(sampleRate * 2);     // byte rate: 16-bit mono
		buffer.putShort((short) 2);        // block align
		buffer.putShort((short) 16);       // bits per sample
		buffer.put("data".getBytes(StandardCharsets.US_ASCII));
		buffer.putInt(pcm.length);
		buffer.put(pcm);
		return buffer.array();
	}

	/**
	 * Overlapping tones with a struck-note envelope.
	 *
	 * <p>Each note fades in fast and decays slowly, like something plucked, and
	 * the notes overlap so the arpeggio rings as one chord rather than three
	 * separate beeps. A quiet octave above each note adds brightness without
	 * changing the pitch that is heard.</p>
	 */
	private byte[] render(double[] notes, double amplitude)
	{
		int perNote = (int) (SAMPLE_RATE * NOTE_MS / 1000.0);
		int step = (int) (perNote * (1.0 - OVERLAP));
		// the last note rings on after the others have finished
		int total = step * (notes.length - 1) + perNote * 3;
		double[] mix = new double[total];
		for (int n = 0; n < notes.length; n++)
		{
			double hz = notes[n];
			int start = n * step;
			// later notes ring longer, so the run settles instead of stopping
			int length = Math.min(total - start, perNote * (n == notes.length - 1 ? 3 : 2));
			for (int i = 0; i < length; i++)
			{
				double progress = (double) i / length;
				// 4 ms attack, then exponential decay: a struck note, not a beep
				double attack = Math.min(1.0, i / (SAMPLE_RATE * 0.004));
				double decay = Math.exp(-3.2 * progress);
				double t = i / SAMPLE_RATE;
				double fundamental = Math.sin(2.0 * Math.PI * hz * t);
				double octave = Math.sin(2.0 * Math.PI * hz * 2.0 * t) * 0.18;
				mix[start + i] += (fundamental + octave) * attack * decay;
			}
		}
		// the decay does not quite reach silence, so fade the last 8 ms to zero
		// rather than cutting mid-wave, which clicks
		int fade = Math.min(total, (int) (SAMPLE_RATE * 0.008));
		for (int i = 0; i < fade; i++)
		{
			mix[total - fade + i] *= 1.0 - (double) i / fade;
		}
		double peak = 0.0;
		for (double v : mix)
		{
			peak = Math.max(peak, Math.abs(v));
		}
		double scale = peak > 0 ? amplitude * 0.5 / peak : 0.0;
		byte[] out = new byte[total * 2];
		for (int i = 0; i < total; i++)
		{
			short value = (short) (mix[i] * scale * Short.MAX_VALUE);
			out[i * 2] = (byte) (value & 0xff);
			out[i * 2 + 1] = (byte) ((value >> 8) & 0xff);
		}
		return out;
	}
}
