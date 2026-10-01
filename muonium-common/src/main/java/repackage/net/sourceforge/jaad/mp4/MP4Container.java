package repackage.net.sourceforge.jaad.mp4;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import repackage.net.sourceforge.jaad.mp4.api.Brand;
import repackage.net.sourceforge.jaad.mp4.api.Movie;
import repackage.net.sourceforge.jaad.mp4.boxes.Box;
import repackage.net.sourceforge.jaad.mp4.boxes.BoxFactory;
import repackage.net.sourceforge.jaad.mp4.boxes.BoxTypes;
import repackage.net.sourceforge.jaad.mp4.boxes.impl.FileTypeBox;
import repackage.net.sourceforge.jaad.mp4.boxes.impl.ProgressiveDownloadInformationBox;

/**
 * MP4Container 是 MP4 解复用器的核心类。它读取
 * 容器并提供对其中所含数据的访问。
 *
 * 数据源可以是 <code>InputStream</code> 或
 * <code>RandomAccessFile</code>。由于规范并未规定内容的
 * 特定顺序，解析所需的数据（采样
 * 表）可能位于流的末尾。这种情况下需要随机访问，
 * 而从 <code>InputSteam</code> 读取将抛出异常。
 * 因此，只要可能，对于本地文件应使用 <code>RandomAccessFile</code>。
 * 当从网络流读取时，从 <code>InputStream</code> 解析才有用。
 *
 * 每个 <code>MP4Container</code> 可返回所使用的文件品牌（文件格式
 * 版本）。可选地，还可能存在以下数据：
 * <ul>
 * <li>渐进式下载信息：下载速率与播放
 * 延迟的成对数据，见 {@link #getDownloadInformationPairs() getDownloadInformationPairs()}</li>
 * <li>一个 <code>Movie</code></li>
 * </ul>
 *
 * 此外它还提供对底层 MP4 box 的访问，可通过
 * <code>getBoxes()</code> 获取。但不建议直接访问这些 box。
 * 
 * @author in-somnia
 */
public class MP4Container {

	static {
		Logger log = Logger.getLogger("MP4 API");
		for(Handler h : log.getHandlers()) {
			log.removeHandler(h);
		}
		log.setLevel(Level.WARNING);

		final ConsoleHandler h = new ConsoleHandler();
		h.setLevel(Level.ALL);
		log.addHandler(h);
	}
	private final MP4InputStream in;
	private final List<Box> boxes;
	private Brand major, minor;
	private Brand[] compatible;
	private FileTypeBox ftyp;
	private ProgressiveDownloadInformationBox pdin;
	private Box moov;
	private Movie movie;

	public MP4Container(InputStream in) throws IOException {
		this.in = new MP4InputStream(in);
		boxes = new ArrayList<Box>();

		readContent();
	}

	public MP4Container(RandomAccessFile in) throws IOException {
		this.in = new MP4InputStream(in);
		boxes = new ArrayList<Box>();

		readContent();
	}

	private void readContent() throws IOException {
		//读取所有 box
		Box box = null;
		long type;
		boolean moovFound = false;
		while(in.hasLeft()) {
			box = BoxFactory.parseBox(null, in);
			if(boxes.isEmpty()&&box.getType()!=BoxTypes.FILE_TYPE_BOX) throw new MP4Exception("no MP4 signature found");
			boxes.add(box);

			type = box.getType();
			if(type==BoxTypes.FILE_TYPE_BOX) {
				if(ftyp==null) ftyp = (FileTypeBox) box;
			}
			else if(type==BoxTypes.MOVIE_BOX) {
				if(movie==null) moov = box;
				moovFound = true;
			}
			else if(type==BoxTypes.PROGRESSIVE_DOWNLOAD_INFORMATION_BOX) {
				if(pdin==null) pdin = (ProgressiveDownloadInformationBox) box;
			}
			else if(type==BoxTypes.MEDIA_DATA_BOX) {
				if(moovFound) break;
				else if(!in.hasRandomAccess()) throw new MP4Exception("movie box at end of file, need random access");
			}
		}
	}

	public Brand getMajorBrand() {
		if(major==null) major = Brand.forID(ftyp.getMajorBrand());
		return major;
	}

	public Brand getMinorBrand() {
		if(minor==null) minor = Brand.forID(ftyp.getMajorBrand());
		return minor;
	}

	public Brand[] getCompatibleBrands() {
		if(compatible==null) {
			final String[] s = ftyp.getCompatibleBrands();
			compatible = new Brand[s.length];
			for(int i = 0; i<s.length; i++) {
				compatible[i] = Brand.forID(s[i]);
			}
		}
		return compatible;
	}

	//TODO: pdin、movie 片段？？
	public Movie getMovie() {
		if(moov==null) return null;
		else if(movie==null) movie = new Movie(moov, in);
		return movie;
	}

	public List<Box> getBoxes() {
		return Collections.unmodifiableList(boxes);
	}
}
