package repackage.net.sourceforge.jaad.aac.syntax;

import java.util.logging.Logger;

public interface Constants {

	Logger LOGGER = Logger.getLogger("jaad"); //用于调试
	int MAX_ELEMENTS = 16;
	int BYTE_MASK = 0xFF;
	int MIN_INPUT_SIZE = 768; //6144 位/声道
	//帧长度
	int WINDOW_LEN_LONG = 1024;
	int WINDOW_LEN_SHORT = WINDOW_LEN_LONG/8;
	int WINDOW_SMALL_LEN_LONG = 960;
	int WINDOW_SMALL_LEN_SHORT = WINDOW_SMALL_LEN_LONG/8;
	//元素类型
	int ELEMENT_SCE = 0;
	int ELEMENT_CPE = 1;
	int ELEMENT_CCE = 2;
	int ELEMENT_LFE = 3;
	int ELEMENT_DSE = 4;
	int ELEMENT_PCE = 5;
	int ELEMENT_FIL = 6;
	int ELEMENT_END = 7;
	//最大数量
	int MAX_WINDOW_COUNT = 8;
	int MAX_WINDOW_GROUP_COUNT = MAX_WINDOW_COUNT;
	int MAX_LTP_SFB = 40;
	int MAX_SECTIONS = 120;
	int MAX_MS_MASK = 128;
	float SQRT2 = 1.414213562f;
}
