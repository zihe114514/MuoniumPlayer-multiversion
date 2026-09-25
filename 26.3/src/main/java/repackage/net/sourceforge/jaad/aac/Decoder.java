package repackage.net.sourceforge.jaad.aac;

import repackage.net.sourceforge.jaad.aac.syntax.BitStream;
import repackage.net.sourceforge.jaad.aac.syntax.Constants;
import repackage.net.sourceforge.jaad.aac.syntax.PCE;
import repackage.net.sourceforge.jaad.aac.syntax.SyntacticElements;
import repackage.net.sourceforge.jaad.aac.filterbank.FilterBank;
import repackage.net.sourceforge.jaad.aac.transport.ADIFHeader;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;

/**
 * AAC 解码器主类
 * @author in-somnia
 */
public class Decoder implements Constants {

	static {
		for(Handler h : LOGGER.getHandlers()) {
			LOGGER.removeHandler(h);
		}
		LOGGER.setLevel(Level.WARNING);

		final ConsoleHandler h = new ConsoleHandler();
		h.setLevel(Level.ALL);
		LOGGER.addHandler(h);
	}
	private final DecoderConfig config;
	private final SyntacticElements syntacticElements;
	private final FilterBank filterBank;
	private BitStream in;
	private ADIFHeader adifHeader;

	/**
	 * 如果解码器支持某个 profile，该方法返回 true。
	 * @param profile 一个 AAC profile
	 * @return 如果指定的 profile 可被解码则返回 true
	 * @see Profile#isDecodingSupported()
	 */
	public static boolean canDecode(Profile profile) {
		return profile.isDecodingSupported();
	}

	/**
	 * 使用 MP4 解码器特定信息初始化解码器。
	 *
	 * 此后即可将 MP4 帧传入
	 * <code>decodeFrame(byte[], SampleBuffer)</code> 方法进行解码。
	 * 
	 * @param decoderSpecificInfo 一个字节数组，包含来自 MP4 容器的解码器特定信息
	 * @throws AACException 如果指定的 profile 不受支持
	 */
	public Decoder(byte[] decoderSpecificInfo) throws AACException {
		config = DecoderConfig.parseMP4DecoderSpecificInfo(decoderSpecificInfo);
		if(config==null) throw new IllegalArgumentException("illegal MP4 decoder specific info");

		if(!canDecode(config.getProfile())) throw new AACException("unsupported profile: "+config.getProfile().getDescription());

		syntacticElements = new SyntacticElements(config);
		filterBank = new FilterBank(config.isSmallFrameUsed(), config.getChannelConfiguration().getChannelCount());

		in = new BitStream();

		LOGGER.log(Level.FINE, "profile: {0}", config.getProfile());
		LOGGER.log(Level.FINE, "sf: {0}", config.getSampleFrequency().getFrequency());
		LOGGER.log(Level.FINE, "channels: {0}", config.getChannelConfiguration().getDescription());
	}

	public DecoderConfig getConfig() {
		return config;
	}

	/**
	 * 在帧模式下解码一帧 AAC 数据，并返回原始 PCM
	 * 数据。
	 * @param frame 该 AAC 帧
	 * @param buffer 用于保存解码后 PCM 数据的缓冲区
	 * @throws AACException 如果解码失败
	 */
	public void decodeFrame(byte[] frame, SampleBuffer buffer) throws AACException {
		if(frame!=null) in.setData(frame);
		try {
			decode(buffer);
		}
		catch(AACException e) {
			if(!e.isEndOfStream()) throw e;
			else LOGGER.log(Level.WARNING,"unexpected end of frame",e);
		}
	}

	private void decode(SampleBuffer buffer) throws AACException {
		if(ADIFHeader.isPresent(in)) {
			adifHeader = ADIFHeader.readHeader(in);
			final PCE pce = adifHeader.getFirstPCE();
			config.setProfile(pce.getProfile());
			config.setSampleFrequency(pce.getSampleFrequency());
			config.setChannelConfiguration(ChannelConfiguration.forInt(pce.getChannelCount()));
		}

		if(!canDecode(config.getProfile())) throw new AACException("unsupported profile: "+config.getProfile().getDescription());

		syntacticElements.startNewFrame();

		try {
			//1: 比特流解析与无损编码
			syntacticElements.decode(in);
			//2: 频谱处理
			syntacticElements.process(filterBank);
			//3: 发送到输出缓冲区
			syntacticElements.sendToOutput(buffer);
		}
		catch(AACException e) {
			buffer.setData(new byte[0], 0, 0, 0, 0);
			throw e;
		}
		catch(Exception e) {
			buffer.setData(new byte[0], 0, 0, 0, 0);
			throw new AACException(e);
		}
	}
}
