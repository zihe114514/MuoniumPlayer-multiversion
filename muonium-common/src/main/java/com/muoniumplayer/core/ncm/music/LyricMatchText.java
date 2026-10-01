package com.muoniumplayer.core.ncm.music;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 歌词自动匹配用的文本归一化与相似度工具。
 *
 * <p>跨平台搜到的同一首歌,标题与歌手写法几乎总是不一致:港台区是繁体、日文曲名写假名、各平台的
 * 标点与空格习惯不同、多歌手的连接符各异。这里先把这些差异抹平,上层的 {@link LyricMatchScore}
 * 才是在比较「内容」而不是「写法」。</p>
 *
 * <p>归一化做三件事(与 本项目 的 {@code utils/lyrics/matchScore.ts} 对齐):</p>
 * <ol>
 *   <li><b>假名 → 罗马音</b>:{@code ヨルシカ} 与 {@code Yorushika} 归一化后是同一个串。注意这只对
 *       假名有效,汉字(如 {@code 米津玄師})不转——和 wanakana 的行为一致。</li>
 *   <li><b>繁体 → 简体</b>:内置 OpenCC {@code TSCharacters} 的单字映射表(2966 组),台版/港版曲名
 *       不再因为字形不同被判 0 分。</li>
 *   <li><b>大小写、标点、符号、空白全部抹掉</b>。</li>
 * </ol>
 *
 * <p>标题另有一条专用规则:括号里的 {@code feat. / ft.} 一律丢掉,但括号里的<b>版本标记</b>
 * ({@code live / remix / instrumental / 伴奏 / カラオケ / remaster ...})必须保留。否则
 * {@code Song (Live ver.)} 会被当成 {@code Song},把演唱会版的时间轴贴到录音室版上。</p>
 *
 * <p>本类只做纯计算,不持有状态、不发起网络请求,任何输入都不抛异常。</p>
 */
final class LyricMatchText {

    private LyricMatchText() {
    }

    // ── 繁 → 简 ────────────────────────────────────────────────────────────
    // 数据来源:OpenCC data/dictionary/TSCharacters.txt(单字条目,取每个繁体字的第一个简体写法,
    // 去掉简繁同形的条目)。两条串按下标一一对应,加载时摊成一张 65536 项的码点表,之后查表是 O(1)。

    private static final String T2S_TRADITIONAL =
            "㑯㑳㑶㓨㘚㜄㜏㠏㥮㩜㩳㩵䁻䃮䊷䋙䋚䋹䋻䍦䎱䙡䜀䝼䥇䥑䥱䦛䦟䯀䰾䱷䱽䲁䲘䴉丟並乾亂亙亞佇佈佔併來侖侶侷俁係俔俠俥俬倀倆倈倉個們倖倫倲偉偑側偵偽傌傑傖傘備傢傭傯傳傴債傷傾僂僅僉僑僕僞僥僨僱價儀儁儂億儈儉儎儐儔儕儘償優儲儷儸儺儻儼兇兌兒兗內兩冊冑冪凈凍凜凱別刪剄則剋剎剗剛剝剮剴創剷劃劄劇劉劊劌劍劏劑劚勁動務勛勝勞勢勩勱勳勵勸勻匭匯匱區協卹卻卽厙厠厤厭厲厴參叄叢吒吳吶呂咼員唄唸問啓啞啟啢喎喚喪喫喬單喲嗆嗇嗊嗎嗚嗩嗶嘆嘍嘓嘔嘖嘗嘜嘩嘮嘯嘰嘵嘸嘽噁噓噚噝噠噥噦噯噲噴噸噹嚀嚇嚌嚐嚕嚙嚥嚦嚨嚮嚲嚳嚴嚶囀囁囂囅囈囉囌囑囪圇國圍園圓圖團垻埡埰執堅堊堖堝堯報場塊塋塏塒塗塚塢塤塵塹墊墜墮墰墳墶墻墾壇壋壎壓壘壙壚壜壞壟"
            + "壠壢壩壪壯壺壼壽夠夢夥夾奐奧奩奪奬奮奼妝姍姦娛婁婦婭媧媯媰媼媽嫋嫗嫵嫺嫻嫿嬀嬃嬈嬋嬌嬙嬡嬤嬪嬰嬸孃孋孌孫學孿宮寀寢實寧審寫寬寵寶將專尋對導尷屆屍屓屜屢層屨屬岡峯峴島峽崍崑崗崙崢崬嵐嵗嵾嶁嶄嶇嶔嶗嶠嶢嶧嶨嶮嶸嶺嶼嶽巋巒巔巖巰巹帥師帳帶幀幃幓幗幘幟幣幫幬幷幹幾庫廁廂廄廈廎廕廚廝廟廠廡廢廣廩廬廳弒弔弳張強彆彈彌彎彔彙彠彥彫彲彿後徑從徠復徵徹恆恥悅悞悵悶悽惡惱惲惻愛愜愨愴愷愾慄態慍慘慚慟慣慤慪慫慮慳慶慺慼慾憂憊憐憑憒憖憚憤憫憮憲憶懇應懌懍懞懟懣懤懨懲懶懷懸懺懼懾戀戇戔戧戩戰戱戲戶扞拋拚挩挱挾捨捫捱捲掃掄掆掗掙掛採揀揚換揮揯損搖搗搧搵搶摑摜摟摯摳摶摺摻撈撏撐撓撝撟撣撥撫撲撳撻撾撿擁擄擇擊擋擓擔據擠擡擣擬"
            + "擯擰擱擲擴擷擺擻擼擽擾攄攆攏攔攖攙攛攜攝攢攣攤攪攬敎敓敗敘敵數斂斃斆斕斬斷於旂旣昇時晉晝暈暉暘暢暫曄曆曇曉曏曖曠曨曬書會朧朮東枴柵柺査桿梔梘條梟梲棄棊棖棗棟棡棧棲棶椏椲楊楓楨業極榘榦榪榮榲榿構槍槓槤槧槨槮槳槶槼樁樂樅樑樓標樞樢樣樧樫樳樸樹樺樿橈橋機橢橫檁檉檔檜檟檢檣檮檯檳檸檻櫃櫓櫚櫛櫝櫞櫟櫥櫧櫨櫪櫫櫬櫱櫳櫸櫻欄欅權欏欒欖欞欽歎歐歟歡歲歷歸歿殘殞殤殨殫殭殮殯殰殲殺殻殼毀毆毿氂氈氌氣氫氬氳氾汎汙決沒沖況泝洩洶浹涇涗涼淒淚淥淨淩淪淵淶淺渙減渢渦測渾湊湞湧湯溈準溝溫溮溳溼滄滅滌滎滙滬滯滲滷滸滻滾滿漁漊漚漢漣漬漲漵漸漿潁潑潔潙潚潛潤潯潰潷潿澀澆澇澐澗澠澤澦澩澮澱澾濁濃濄濕濘濚濛濜濟濤濧濫濰濱濺濼濾瀂瀅瀆"
            + "瀇瀉瀋瀏瀕瀘瀝瀟瀠瀦瀧瀨瀰瀲瀾灃灄灑灕灘灝灡灣灤灧灩災為烏烴無煉煒煙煢煥煩煬煱熅熒熗熱熲熾燁燈燉燒燙燜營燦燬燭燴燶燻燼燾爍爐爛爭爲爺爾牀牆牘牴牽犖犛犢犧狀狹狽猙猶猻獁獃獄獅獎獨獪獫獮獰獱獲獵獷獸獺獻獼玀現琱琺琿瑋瑒瑣瑤瑩瑪瑲璉璡璣璦璫璯環璵璸璽璿瓊瓏瓔瓚甌甕產産畝畢畫異畵當疇疊痙痠痾瘂瘋瘍瘓瘞瘡瘧瘮瘲瘺瘻療癆癇癉癒癘癟癡癢癤癥癧癩癬癭癮癰癱癲發皁皚皰皸皺盃盜盞盡監盤盧盪眞眥眾睏睜睞瞘瞜瞞瞶瞼矇矓矚矯硃硜硤硨硯碕碩碭碸確碼碽磑磚磠磣磧磯磽磾礄礎礙礦礪礫礬礱祕祿禍禎禕禡禦禪禮禰禱禿秈稅稈稏稜稟種稱穀穇穌積穎穠穡穢穩穫穭窩窪窮窯窵窶窺竄竅竇竈竊竪競筆筍筧筴箇箋箏箚節範築篋篔篠篤篩篳簀簍簑簞簡簣簫簹簽"
            + "簾籃籌籔籙籛籜籟籠籤籩籪籬籮籲粵糉糝糞糧糰糲糴糶糹糾紀紂約紅紆紇紈紉紋納紐紓純紕紖紗紘紙級紛紜紝紡紬紮細紱紲紳紵紹紺紼紿絀終絃組絅絆絎結絕絛絝絞絡絢給絨絰統絲絳絶絹綁綃綆綈綉綌綏綐綑經綜綞綠綢綣綫綬維綯綰綱網綳綴綵綸綹綺綻綽綾綿緄緇緊緋緑緒緓緔緗緘緙線緝緞締緡緣緦編緩緬緯緱緲練緶緹緻緼縈縉縊縋縐縑縕縗縛縝縞縟縣縧縫縭縮縱縲縳縴縵縶縷縹總績繃繅繆繒織繕繚繞繡繢繩繪繫繭繮繯繰繳繸繹繼繽繾繿纇纈纊續纍纏纓纔纖纘纜缽罃罈罌罎罰罵罷羅羆羈羋羣羥羨義羶習翫翬翹翽耬耮聖聞聯聰聲聳聵聶職聹聽聾肅脅脈脛脣脩脫脹腎腖腡腦腫腳腸膃膕膚膞膠膩膽膾膿臉臍臏臘臚臟臠臢臥臨臺與興舉舊舖舘艙艤艦艫艱艷芻苧茲荊莊莖莢莧華菴菸萇"
            + "萊萬萴萵葉葒葤葦葯葷蒐蒓蒔蒕蒞蒼蓀蓆蓋蓮蓯蓴蓽蔔蔘蔞蔣蔥蔦蔭蕁蕆蕎蕒蕓蕕蕘蕢蕩蕪蕭蕷薀薈薊薌薑薔薘薟薦薩薳薴薵薹薺藍藎藝藥藪藭藴藶藹藺蘀蘄蘆蘇蘊蘋蘚蘞蘢蘭蘺蘿虆處虛虜號虧虯蛺蛻蜆蝕蝟蝦蝨蝸螄螞螢螮螻螿蟄蟈蟎蟣蟬蟯蟲蟶蟻蠁蠅蠆蠍蠐蠑蠔蠟蠣蠨蠱蠶蠻衆衊術衕衚衛衝袞袷裊裏補裝裡製複褌褘褲褳褸褻襇襉襏襖襝襠襤襪襬襯襲襴覈見覎規覓視覘覡覥覦親覬覯覲覷覺覽覿觀觴觶觸訁訂訃計訊訌討訐訒訓訕訖託記訛訝訟訢訣訥訩訪設許訴訶診註証詁詆詎詐詒詔評詖詗詘詛詞詠詡詢詣試詩詫詬詭詮詰話該詳詵詼詿誄誅誆誇誌認誑誒誕誘誚語誠誡誣誤誥誦誨說説誰課誶誹誼誾調諂諄談諉請諍諏諑諒論諗諛諜諝諞諡諢諤諦諧諫諭諮諱諳諶諷諸諺諼諾謀謁謂謄謅"
            + "謊謎謐謔謖謗謙謚講謝謠謡謨謫謬謭謳謹謾譁證譎譏譖識譙譚譜譟譫譭譯議譴護譸譽譾讀讅變讋讌讎讒讓讕讖讚讜讞谿豈豎豐豔豬豶貍貓貙貝貞貟負財貢貧貨販貪貫責貯貰貲貳貴貶買貸貺費貼貽貿賀賁賂賃賄賅資賈賊賑賒賓賕賙賚賜賞賠賡賢賣賤賦賧質賫賬賭賰賴賵賺賻購賽賾贄贅贇贈贊贋贍贏贐贓贔贖贗贛贜赬趕趙趨趲跡踐踰踴蹌蹕蹟蹠蹣蹤蹺躂躉躊躋躍躎躑躒躓躕躚躡躥躦躪軀車軋軌軍軑軒軔軛軟軤軫軲軸軹軺軻軼軾較輅輇輈載輊輒輓輔輕輛輜輝輞輟輥輦輩輪輬輯輳輸輻輼輾輿轀轂轄轅轆轉轍轎轔轟轡轢轤辦辭辮辯農迴逕這連週進遊運過達違遙遜遞遠遡適遲遶遷選遺遼邁還邇邊邏邐郟郵鄆鄉鄒鄔鄖鄧鄭鄰鄲鄴鄶鄺酇酈醃醖醜醞醟醣醫醬醱釀釁釃釅釋釐釒釓釔釕釗釘釙針"
            + "釣釤釦釧釩釵釷釹釺釾鈀鈁鈃鈄鈅鈈鈉鈍鈎鈐鈑鈒鈔鈕鈞鈡鈣鈥鈦鈧鈮鈰鈳鈴鈷鈸鈹鈺鈽鈾鈿鉀鉅鉆鉈鉉鉋鉍鉑鉕鉗鉚鉛鉞鉢鉤鉦鉬鉭鉳鉶鉸鉺鉻鉿銀銃銅銍銑銓銖銘銚銛銜銠銣銥銦銨銩銪銫銬銱銳銷銹銻銼鋁鋃鋅鋇鋌鋏鋒鋙鋝鋟鋣鋤鋥鋦鋨鋩鋪鋭鋮鋯鋰鋱鋶鋸鋼錁錄錆錇錈錏錐錒錕錘錙錚錛錟錠錡錢錦錨錩錫錮錯録錳錶錸錼鍀鍁鍃鍅鍆鍇鍈鍊鍋鍍鍔鍘鍚鍛鍠鍤鍥鍩鍬鍰鍵鍶鍺鍼鍾鎂鎄鎇鎊鎌鎔鎖鎘鎚鎛鎡鎢鎣鎦鎧鎩鎪鎬鎭鎮鎰鎲鎳鎵鎶鎸鎿鏃鏇鏈鏌鏍鏐鏑鏗鏘鏜鏝鏞鏟鏡鏢鏤鏨鏰鏵鏷鏹鏺鏽鐃鐋鐐鐒鐓鐔鐘鐙鐝鐠鐥鐦鐧鐨鐫鐮鐯鐲鐳鐵鐶鐸鐺鐿鑄鑊鑌鑑鑒鑔鑕鑞鑠鑣鑥鑭鑰鑱鑲鑷鑹鑼鑽鑾鑿钁钂長門閂閃閆閈閉開閌閎閏閑閒間閔閘閡閣閤閥閨閩閫閬閭閱閲閶閹閻閼閽閾閿"
            + "闃闆闇闈闊闋闌闍闐闒闓闔闕闖關闞闠闡闢闤闥陘陝陞陣陰陳陸陽隉隊階隕際隨險隯隱隴隸隻雋雖雙雛雜雞離難雲電霑霢霧霽靂靄靆靈靉靚靜靝靦靨鞏鞝鞦鞽韁韃韆韉韋韌韍韓韙韜韝韞韻響頁頂頃項順頇須頊頌頎頏預頑頒頓頗領頜頡頤頦頭頮頰頲頴頷頸頹頻頽顆題額顎顏顒顓顔願顙顛類顢顥顧顫顬顯顰顱顳顴風颭颮颯颱颳颶颸颺颻颼飀飄飆飈飛飠飢飣飥飩飪飫飭飯飱飲飴飼飽飾飿餃餄餅餈餉養餌餎餏餑餒餓餕餖餘餚餛餜餞餡館餬餱餳餵餶餷餺餼餾餿饁饃饅饈饉饊饋饌饑饒饗饜饞饢馬馭馮馱馳馴馹駁駐駑駒駔駕駘駙駛駝駟駡駢駭駰駱駸駿騁騂騅騌騍騎騏騖騙騤騧騫騭騮騰騶騷騸騾驀驁驂驃驄驅驊驌驍驏驕驗驚驛驟驢驤驥驦驪驫骯髏髒體髕髖髮鬆鬍鬚鬢鬥鬧鬨鬩鬮鬱鬹魎魘魚魛"
            + "魢魨魯魴魷魺鮁鮃鮊鮋鮍鮎鮐鮑鮒鮓鮚鮜鮝鮞鮣鮦鮪鮫鮭鮮鮳鮶鮺鯀鯁鯇鯉鯊鯒鯔鯕鯖鯗鯛鯝鯡鯢鯤鯧鯨鯪鯫鯰鯴鯷鯽鯿鰁鰂鰃鰆鰈鰉鰌鰍鰏鰐鰒鰓鰛鰜鰟鰠鰣鰥鰧鰨鰩鰭鰮鰱鰲鰳鰵鰷鰹鰺鰻鰼鰾鱂鱅鱈鱉鱒鱔鱖鱗鱘鱝鱟鱠鱣鱤鱧鱨鱭鱯鱷鱸鱺鳥鳧鳩鳬鳲鳳鳴鳶鳾鴆鴇鴉鴒鴕鴛鴝鴞鴟鴣鴦鴨鴯鴰鴴鴷鴻鴿鵁鵂鵃鵐鵑鵒鵓鵜鵝鵠鵡鵪鵬鵮鵯鵰鵲鵷鵾鶄鶇鶉鶊鶓鶖鶘鶚鶡鶥鶩鶪鶬鶯鶲鶴鶹鶺鶻鶼鶿鷀鷁鷂鷄鷉鷊鷓鷖鷗鷙鷚鷥鷦鷫鷯鷲鷳鷴鷸鷹鷺鷽鸂鸇鸊鸌鸏鸕鸘鸚鸛鸝鸞鹵鹹鹺鹼鹽麗麥麩麪麫麯麴麵麼麽黃黌點黨黲黴黶黷黽黿鼂鼉鼕鼴齊齋齎齏齒齔齕齗齙齜齟齠齡齣齦齧齪齬齲齶齷龍龎龐龑龔龕龜鿁鿓";

    private static final String T2S_SIMPLIFIED =
            "㑔㑇㐹刾㘎㚯㛣㟆㤘㨫㧐擜䀥鿎䌶䌺䌻䌿䌾䍠䎬䙌䜧䞍䦂鿏䥾䦶䦷䯅鲃䲣䲝鳚鳤鹮丢并干乱亘亚伫布占并来仑侣局俣系伣侠伡私伥俩俫仓个们幸伦㑈伟㐽侧侦伪㐷杰伧伞备家佣偬传伛债伤倾偻仅佥侨仆伪侥偾雇价仪俊侬亿侩俭傤傧俦侪尽偿优储俪㑩傩傥俨凶兑儿兖内两册胄幂净冻凛凯别删刭则克刹刬刚剥剐剀创铲划札剧刘刽刿剑㓥剂㔉劲动务勋胜劳势勚劢勋励劝匀匦汇匮区协恤却即厍厕历厌厉厣参叁丛咤吴呐吕呙员呗念问启哑启唡㖞唤丧吃乔单哟呛啬唝吗呜唢哔叹喽啯呕啧尝唛哗唠啸叽哓呒啴恶嘘㖊咝哒哝哕嗳哙喷吨当咛吓哜尝噜啮咽呖咙向亸喾严嘤啭嗫嚣冁呓啰苏嘱囱囵国围园圆图团坝垭采执坚垩垴埚尧报场块茔垲埘涂冢坞埙尘堑垫坠堕坛坟垯墙垦坛垱埙压垒圹垆坛坏垄"
            + "垅坜坝塆壮壶壸寿够梦伙夹奂奥奁夺奖奋姹妆姗奸娱娄妇娅娲妫㛀媪妈袅妪妩娴娴婳妫媭娆婵娇嫱嫒嬷嫔婴婶娘㛤娈孙学孪宫采寝实宁审写宽宠宝将专寻对导尴届尸屃屉屡层屦属冈峰岘岛峡崃昆岗仑峥岽岚岁㟥嵝崭岖嵚崂峤峣峄峃崄嵘岭屿岳岿峦巅岩巯卺帅师帐带帧帏㡎帼帻帜币帮帱并干几库厕厢厩厦庼荫厨厮庙厂庑废广廪庐厅弑吊弪张强别弹弥弯录汇彟彦雕彨佛后径从徕复征彻恒耻悦悮怅闷凄恶恼恽恻爱惬悫怆恺忾栗态愠惨惭恸惯悫怄怂虑悭庆㥪戚欲忧惫怜凭愦慭惮愤悯怃宪忆恳应怿懔蒙怼懑㤽恹惩懒怀悬忏惧慑恋戆戋戗戬战戯戏户捍抛拼捝挲挟舍扪挨卷扫抡㧏挜挣挂采拣扬换挥搄损摇捣扇揾抢掴掼搂挚抠抟折掺捞挦撑挠㧑挢掸拨抚扑揿挞挝捡拥掳择击挡㧟担据挤抬捣拟"
            + "摈拧搁掷扩撷摆擞撸㧰扰摅撵拢拦撄搀撺携摄攒挛摊搅揽教敚败叙敌数敛毙敩斓斩断于旗既升时晋昼晕晖旸畅暂晔历昙晓向暧旷昽晒书会胧术东拐栅拐查杆栀枧条枭棁弃棋枨枣栋㭎栈栖梾桠㭏杨枫桢业极矩干杩荣榅桤构枪杠梿椠椁椮桨椢椝桩乐枞梁楼标枢㭤样榝㭴桪朴树桦椫桡桥机椭横檩柽档桧槚检樯梼台槟柠槛柜橹榈栉椟橼栎橱槠栌枥橥榇蘖栊榉樱栏榉权椤栾榄棂钦叹欧欤欢岁历归殁残殒殇㱮殚僵殓殡㱩歼杀壳壳毁殴毵牦毡氇气氢氩氲泛泛污决没冲况溯泄汹浃泾涚凉凄泪渌净凌沦渊涞浅涣减沨涡测浑凑浈涌汤沩准沟温浉涢湿沧灭涤荥汇沪滞渗卤浒浐滚满渔溇沤汉涟渍涨溆渐浆颍泼洁沩㴋潜润浔溃滗涠涩浇涝沄涧渑泽滪泶浍淀㳠浊浓㳡湿泞溁蒙浕济涛㳔滥潍滨溅泺滤澛滢渎"
            + "㲿泻沈浏濒泸沥潇潆潴泷濑弥潋澜沣滠洒漓滩灏㳕湾滦滟滟灾为乌烃无炼炜烟茕焕烦炀㶽煴荧炝热颎炽烨灯炖烧烫焖营灿毁烛烩㶶熏烬焘烁炉烂争为爷尔床墙牍抵牵荦牦犊牺状狭狈狰犹狲犸呆狱狮奖独狯猃狝狞㺍获猎犷兽獭献猕猡现雕珐珲玮玚琐瑶莹玛玱琏琎玑瑷珰㻅环玙瑸玺璇琼珑璎瓒瓯瓮产产亩毕画异画当畴叠痉酸疴痖疯疡痪瘗疮疟瘆疭瘘瘘疗痨痫瘅愈疠瘪痴痒疖症疬癞癣瘿瘾痈瘫癫发皂皑疱皲皱杯盗盏尽监盘卢荡真眦众困睁睐眍䁖瞒瞆睑蒙眬瞩矫朱硁硖砗砚埼硕砀砜确码䂵硙砖硵碜碛矶硗䃅硚础碍矿砺砾矾砻秘禄祸祯祎祃御禅礼祢祷秃籼税秆䅉棱禀种称谷䅟稣积颖秾穑秽稳获穞窝洼穷窑窎窭窥窜窍窦灶窃竖竞笔笋笕䇲个笺筝札节范筑箧筼筿笃筛筚箦篓蓑箪简篑箫筜签"
            + "帘篮筹䉤箓篯箨籁笼签笾簖篱箩吁粤粽糁粪粮团粝籴粜纟纠纪纣约红纡纥纨纫纹纳纽纾纯纰纼纱纮纸级纷纭纴纺䌷扎细绂绁绅纻绍绀绋绐绌终弦组䌹绊绗结绝绦绔绞络绚给绒绖统丝绛绝绢绑绡绠绨绣绤绥䌼捆经综缍绿绸绻线绶维绹绾纲网绷缀彩纶绺绮绽绰绫绵绲缁紧绯绿绪绬绱缃缄缂线缉缎缔缗缘缌编缓缅纬缑缈练缏缇致缊萦缙缢缒绉缣缊缞缚缜缟缛县绦缝缡缩纵缧䌸纤缦絷缕缥总绩绷缫缪缯织缮缭绕绣缋绳绘系茧缰缳缲缴䍁绎继缤缱䍀颣缬纩续累缠缨才纤缵缆钵䓨坛罂坛罚骂罢罗罴羁芈群羟羡义膻习玩翚翘翙耧耢圣闻联聪声耸聩聂职聍听聋肃胁脉胫唇修脱胀肾胨脶脑肿脚肠腽腘肤䏝胶腻胆脍脓脸脐膑腊胪脏脔臜卧临台与兴举旧铺馆舱舣舰舻艰艳刍苎兹荆庄茎荚苋华庵烟苌"
            + "莱万荝莴叶荭荮苇药荤搜莼莳蒀莅苍荪席盖莲苁莼荜卜参蒌蒋葱茑荫荨蒇荞荬芸莸荛蒉荡芜萧蓣蕰荟蓟芗姜蔷荙莶荐萨䓕苧䓓苔荠蓝荩艺药薮䓖蕴苈蔼蔺萚蕲芦苏蕴苹藓蔹茏兰蓠萝蔂处虚虏号亏虬蛱蜕蚬蚀猬虾虱蜗蛳蚂萤䗖蝼螀蛰蝈螨虮蝉蛲虫蛏蚁蚃蝇虿蝎蛴蝾蚝蜡蛎蟏蛊蚕蛮众蔑术同胡卫冲衮夹袅里补装里制复裈袆裤裢褛亵裥裥袯袄裣裆褴袜摆衬袭襕核见觃规觅视觇觋觍觎亲觊觏觐觑觉览觌观觞觯触讠订讣计讯讧讨讦讱训讪讫托记讹讶讼䜣诀讷讻访设许诉诃诊注证诂诋讵诈诒诏评诐诇诎诅词咏诩询诣试诗诧诟诡诠诘话该详诜诙诖诔诛诓夸志认诳诶诞诱诮语诚诫诬误诰诵诲说说谁课谇诽谊訚调谄谆谈诿请诤诹诼谅论谂谀谍谞谝谥诨谔谛谐谏谕咨讳谙谌讽诸谚谖诺谋谒谓誊诌"
            + "谎谜谧谑谡谤谦谥讲谢谣谣谟谪谬谫讴谨谩哗证谲讥谮识谯谭谱噪谵毁译议谴护诪誉谫读谉变詟䜩雠谗让谰谶赞谠谳溪岂竖丰艳猪豮狸猫䝙贝贞贠负财贡贫货贩贪贯责贮贳赀贰贵贬买贷贶费贴贻贸贺贲赂赁贿赅资贾贼赈赊宾赇赒赉赐赏赔赓贤卖贱赋赕质赍账赌䞐赖赗赚赙购赛赜贽赘赟赠赞赝赡赢赆赃赑赎赝赣赃赪赶赵趋趱迹践逾踊跄跸迹跖蹒踪跷跶趸踌跻跃䟢踯跞踬蹰跹蹑蹿躜躏躯车轧轨军轪轩轫轭软轷轸轱轴轵轺轲轶轼较辂辁辀载轾辄挽辅轻辆辎辉辋辍辊辇辈轮辌辑辏输辐辒辗舆辒毂辖辕辘转辙轿辚轰辔轹轳办辞辫辩农回径这连周进游运过达违遥逊递远溯适迟绕迁选遗辽迈还迩边逻逦郏邮郓乡邹邬郧邓郑邻郸邺郐邝酂郦腌酝丑酝蒏糖医酱酦酿衅酾酽释厘钅钆钇钌钊钉钋针"
            + "钓钐扣钏钒钗钍钕钎䥺钯钫钘钭钥钚钠钝钩钤钣钑钞钮钧钟钙钬钛钪铌铈钶铃钴钹铍钰钸铀钿钾巨钻铊铉铇铋铂钷钳铆铅钺钵钩钲钼钽锫铏铰铒铬铪银铳铜铚铣铨铢铭铫铦衔铑铷铱铟铵铥铕铯铐铞锐销锈锑锉铝锒锌钡铤铗锋铻锊锓铘锄锃锔锇铓铺锐铖锆锂铽锍锯钢锞录锖锫锩铔锥锕锟锤锱铮锛锬锭锜钱锦锚锠锡锢错录锰表铼镎锝锨锪钫钔锴锳炼锅镀锷铡钖锻锽锸锲锘锹锾键锶锗针钟镁锿镅镑镰镕锁镉锤镈镃钨蓥镏铠铩锼镐镇镇镒镋镍镓鿔镌镎镞旋链镆镙镠镝铿锵镗镘镛铲镜镖镂錾镚铧镤镪䥽锈铙铴镣铹镦镡钟镫镢镨䦅锎锏镄镌镰䦃镯镭铁镮铎铛镱铸镬镔鉴鉴镲锧镴铄镳镥镧钥镵镶镊镩锣钻銮凿镢镋长门闩闪闫闬闭开闶闳闰闲闲间闵闸阂阁合阀闺闽阃阆闾阅阅阊阉阎阏阍阈阌"
            + "阒板暗闱阔阕阑阇阗阘闿阖阙闯关阚阓阐辟阛闼陉陕升阵阴陈陆阳陧队阶陨际随险陦隐陇隶只隽虽双雏杂鸡离难云电沾霡雾霁雳霭叇灵叆靓静靔腼靥巩绱秋鞒缰鞑千鞯韦韧韨韩韪韬鞲韫韵响页顶顷项顺顸须顼颂颀颃预顽颁顿颇领颌颉颐颏头颒颊颋颕颔颈颓频颓颗题额颚颜颙颛颜愿颡颠类颟颢顾颤颥显颦颅颞颧风飐飑飒台刮飓飔飏飖飕飗飘飙飚飞饣饥饤饦饨饪饫饬饭飧饮饴饲饱饰饳饺饸饼糍饷养饵饹饻饽馁饿馂饾余肴馄馃饯馅馆糊糇饧喂馉馇馎饩馏馊馌馍馒馐馑馓馈馔饥饶飨餍馋馕马驭冯驮驰驯驲驳驻驽驹驵驾骀驸驶驼驷骂骈骇骃骆骎骏骋骍骓骔骒骑骐骛骗骙䯄骞骘骝腾驺骚骟骡蓦骜骖骠骢驱骅骕骁骣骄验惊驿骤驴骧骥骦骊骉肮髅脏体髌髋发松胡须鬓斗闹哄阋阄郁鬶魉魇鱼鱽"
            + "鱾鲀鲁鲂鱿鲄鲅鲆鲌鲉鲏鲇鲐鲍鲋鲊鲒鲘鲞鲕䲟鲖鲔鲛鲑鲜鲓鲪鲝鲧鲠鲩鲤鲨鲬鲻鲯鲭鲞鲷鲴鲱鲵鲲鲳鲸鲮鲰鲶鲺鳀鲫鳊鳈鲗鳂䲠鲽鳇䲡鳅鲾鳄鳆鳃鳁鳒鳑鳋鲥鳏䲢鳎鳐鳍鳁鲢鳌鳓鳘鲦鲣鲹鳗鳛鳔鳉鳙鳕鳖鳟鳝鳜鳞鲟鲼鲎鲙鳣鳡鳢鲿鲚鳠鳄鲈鲡鸟凫鸠凫鸤凤鸣鸢䴓鸩鸨鸦鸰鸵鸳鸲鸮鸱鸪鸯鸭鸸鸹鸻䴕鸿鸽䴔鸺鸼鹀鹃鹆鹁鹈鹅鹄鹉鹌鹏鹐鹎雕鹊鹓鹍䴖鸫鹑鹒鹋鹙鹕鹗鹖鹛鹜䴗鸧莺鹟鹤鹠鹡鹘鹣鹚鹚鹢鹞鸡䴘鹝鹧鹥鸥鸷鹨鸶鹪鹔鹩鹫鹇鹇鹬鹰鹭鸴㶉鹯䴙鹱鹲鸬鹴鹦鹳鹂鸾卤咸鹾碱盐丽麦麸面面曲曲面么么黄黉点党黪霉黡黩黾鼋鼌鼍冬鼹齐斋赍齑齿龀龁龂龅龇龃龆龄出龈啮龊龉龋腭龌龙厐庞䶮龚龛龟䜤鿒";

    /** 码点 → 简体字;0 表示该字无需转换(简繁同形或不是繁体字)。 */
    private static final char[] TRADITIONAL_TO_SIMPLIFIED = buildTraditionalToSimplified();

    private static char[] buildTraditionalToSimplified() {
        char[] table = new char[0x10000];
        String traditional = T2S_TRADITIONAL;
        String simplified = T2S_SIMPLIFIED;
        int length = Math.min(traditional.length(), simplified.length());
        for (int i = 0; i < length; i++) {
            table[traditional.charAt(i)] = simplified.charAt(i);
        }
        return table;
    }

    private static char toSimplified(char character) {
        char mapped = TRADITIONAL_TO_SIMPLIFIED[character];
        return mapped == 0 ? character : mapped;
    }

    // ── 假名 → 罗马音 ──────────────────────────────────────────────────────
    // 条目形如 "きゃ=kya",空白分隔。平假名与拗音直接列出;片假名在查表前统一折叠成平假名,
    // 只把没有对应平假名的 ヷヸヹヺ 单独列出来。

    private static final Map<String, String> KANA_ROMAJI = buildKanaRomaji();

    private static final String KANA_ROMAJI_TABLE =
            "あ=a い=i う=u え=e お=o ぁ=a ぃ=i ぅ=u ぇ=e ぉ=o"
                    + " か=ka き=ki く=ku け=ke こ=ko ゕ=ka ゖ=ke"
                    + " が=ga ぎ=gi ぐ=gu げ=ge ご=go"
                    + " さ=sa し=shi す=su せ=se そ=so"
                    + " ざ=za じ=ji ず=zu ぜ=ze ぞ=zo"
                    + " た=ta ち=chi つ=tsu て=te と=to"
                    + " だ=da ぢ=ji づ=zu で=de ど=do"
                    + " な=na に=ni ぬ=nu ね=ne の=no"
                    + " は=ha ひ=hi ふ=fu へ=he ほ=ho"
                    + " ば=ba び=bi ぶ=bu べ=be ぼ=bo"
                    + " ぱ=pa ぴ=pi ぷ=pu ぺ=pe ぽ=po"
                    + " ま=ma み=mi む=mu め=me も=mo"
                    + " や=ya ゆ=yu よ=yo ゃ=ya ゅ=yu ょ=yo"
                    + " ら=ra り=ri る=ru れ=re ろ=ro"
                    + " わ=wa ゐ=wi ゑ=we を=wo ゎ=wa ん=n ゔ=vu"
                    + " きゃ=kya きゅ=kyu きょ=kyo ぎゃ=gya ぎゅ=gyu ぎょ=gyo"
                    + " しゃ=sha しゅ=shu しょ=sho しぇ=she"
                    + " じゃ=ja じゅ=ju じょ=jo じぇ=je"
                    + " ちゃ=cha ちゅ=chu ちょ=cho ちぇ=che"
                    + " ぢゃ=ja ぢゅ=ju ぢょ=jo"
                    + " にゃ=nya にゅ=nyu にょ=nyo"
                    + " ひゃ=hya ひゅ=hyu ひょ=hyo"
                    + " びゃ=bya びゅ=byu びょ=byo"
                    + " ぴゃ=pya ぴゅ=pyu ぴょ=pyo"
                    + " みゃ=mya みゅ=myu みょ=myo"
                    + " りゃ=rya りゅ=ryu りょ=ryo"
                    + " ふぁ=fa ふぃ=fi ふぇ=fe ふぉ=fo ふゅ=fyu"
                    + " ゔぁ=va ゔぃ=vi ゔぇ=ve ゔぉ=vo"
                    + " うぃ=wi うぇ=we うぉ=wo いぇ=ye"
                    + " てぃ=ti でぃ=di とぅ=tu どぅ=du"
                    + " つぁ=tsa つぃ=tsi つぇ=tse つぉ=tso"
                    + " くぁ=kwa くぃ=kwi くぇ=kwe くぉ=kwo ぐぁ=gwa"
                    + " すぃ=si ずぃ=zi"
                    + " ヷ=va ヸ=vi ヹ=ve ヺ=vo";

    private static Map<String, String> buildKanaRomaji() {
        Map<String, String> table = new HashMap<String, String>();
        for (String entry : KANA_ROMAJI_TABLE.split(" ")) {
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) continue;
            table.put(entry.substring(0, separator), entry.substring(separator + 1));
        }
        return table;
    }

    /**
     * 假名串转罗马音。促音「っ」本身不发音、把后一个音节的辅音重复一次;长音符「ー」沿用前一个音节的
     * 元音。非假名字符原样保留,所以中英混排的曲名只会被转换其中的假名部分。
     */
    static String toRomaji(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder output = new StringBuilder(value.length() * 2);
        int index = 0;
        while (index < value.length()) {
            char character = value.charAt(index);

            // 长音符要按原字符判断:它折叠成平假名会落到别的码位上。
            if (character == '\u30FC') {
                char vowel = lastVowel(output);
                if (vowel != 0) output.append(vowel);
                index++;
                continue;
            }

            char folded = toHiragana(character);
            if (folded == '\u3063') {          // っ
                String following = romajiAt(value, index + 1);
                if (following != null && following.length() > 0) {
                    char head = following.charAt(0);
                    if (isConsonant(head)) output.append(head);
                }
                index++;
                continue;
            }

            int length = syllableLength(value, index);
            if (length == 0) {
                output.append(character);
                index++;
                continue;
            }
            output.append(syllableRomaji(value, index, length));
            index += length;
        }
        return output.toString();
    }

    /** index 处最长假名音节的长度(2 = 拗音,1 = 普通音节,0 = 不是假名)。 */
    private static int syllableLength(String value, int index) {
        if (index >= value.length()) return 0;
        if (index + 1 < value.length()) {
            String pair = fold(value.charAt(index)) + fold(value.charAt(index + 1));
            if (KANA_ROMAJI.containsKey(pair)) return 2;
        }
        return KANA_ROMAJI.containsKey(fold(value.charAt(index))) ? 1 : 0;
    }

    private static String syllableRomaji(String value, int index, int length) {
        StringBuilder key = new StringBuilder(length);
        for (int i = 0; i < length; i++) key.append(fold(value.charAt(index + i)));
        return KANA_ROMAJI.get(key.toString());
    }

    private static String romajiAt(String value, int index) {
        int length = syllableLength(value, index);
        return length == 0 ? null : syllableRomaji(value, index, length);
    }

    private static String fold(char character) {
        return String.valueOf(toHiragana(character));
    }

    /** 片假名 → 平假名。ヷヸヹヺ 与 ー 不在这个区间里,由调用方单独处理。 */
    private static char toHiragana(char character) {
        if (character >= '\u30A1' && character <= '\u30F6') return (char) (character - 0x60);
        return character;
    }

    private static boolean isConsonant(char character) {
        return character != 'a' && character != 'i' && character != 'u'
                && character != 'e' && character != 'o';
    }

    private static char lastVowel(StringBuilder output) {
        for (int i = output.length() - 1; i >= 0; i--) {
            char character = output.charAt(i);
            if (character == 'a' || character == 'i' || character == 'u'
                    || character == 'e' || character == 'o') {
                return character;
            }
            if (!Character.isLetter(character)) continue;
            // 撞到辅音结尾的音节就说明前一个音节没有元音可延,直接放弃。
            if (character != 'n') return 0;
        }
        return 0;
    }

    // ── 归一化 ─────────────────────────────────────────────────────────────

    /** 通用归一化:假名转罗马音 → 繁转简 → 小写 → 只保留字母与数字。 */
    static String normalize(String value) {
        if (value == null || value.isEmpty()) return "";
        String romanized = toRomaji(value);
        StringBuilder builder = new StringBuilder(romanized.length());
        for (int i = 0; i < romanized.length(); i++) {
            char character = toSimplified(Character.toLowerCase(romanized.charAt(i)));
            if (Character.isLetterOrDigit(character)) builder.append(character);
        }
        return builder.toString();
    }

    private static final Pattern FEAT_BRACKET =
            Pattern.compile("[(\\[（【]\\s*(?iu:feat|featuring|ft)\\.?\\s+[^)\\]）】]+[)\\]）】]");
    private static final Pattern FEAT_TAIL =
            Pattern.compile("(?iu)\\b(?:feat|featuring|ft)\\.?\\s+.*$");
    private static final Pattern BRACKET =
            Pattern.compile("[(\\[（【]([^)\\]）】]+)[)\\]）】]");
    /** 命中这些词的括号内容要保留,否则原曲与 Live/Remix/伴奏版会被判成同一首。 */
    private static final Pattern VERSION_MARKER = Pattern.compile(
            "(?iu)(instrumental|inst|off\\s*vocal|karaoke|remix|mix|version|ver\\.?|cover|live"
                    + "|edit|arrange|伴奏|カラオケ|インスト|リミックス|remaster|remastered)");

    /**
     * 标题专用归一化:{@code (feat. xxx)} 丢掉,含版本标记的括号保留,其余括号内容也丢掉。
     */
    static String normalizeTitle(String value) {
        if (value == null || value.isEmpty()) return "";
        String withoutFeat = FEAT_BRACKET.matcher(value).replaceAll("");
        withoutFeat = FEAT_TAIL.matcher(withoutFeat).replaceAll("");

        Matcher bracket = BRACKET.matcher(withoutFeat);
        StringBuffer buffer = new StringBuffer(withoutFeat.length() + 16);
        while (bracket.find()) {
            String content = bracket.group(1);
            String replacement = VERSION_MARKER.matcher(content).find()
                    ? Matcher.quoteReplacement(bracket.group())
                    : "";
            bracket.appendReplacement(buffer, replacement);
        }
        bracket.appendTail(buffer);
        return normalize(buffer.toString());
    }

    /** 去掉全部括号段落,只用于搜索兜底——搜索要的是更宽的词,匹配判定不能用这个。 */
    static String dropBrackets(String value) {
        if (value == null) return "";
        String stripped = BRACKET.matcher(value).replaceAll(" ").trim();
        return stripped.isEmpty() ? value : stripped;
    }

    // ── 相似度 ─────────────────────────────────────────────────────────────

    static double similarity(String left, String right) {
        return similarity(left, right, false);
    }

    /**
     * 三级相似度,与 Folia 一致:
     * <ol>
     *   <li>归一化后完全相等 → 1.0</li>
     *   <li>互为子串 → 短串长度 / 长串长度(比布尔式的 contains 更细腻)</li>
     *   <li>否则 → 字符集合的 Jaccard 系数,能容忍错字与语序差异</li>
     * </ol>
     *
     * @param titleMode 为 true 时用 {@link #normalizeTitle},即保留括号里的版本标记
     */
    static double similarity(String left, String right, boolean titleMode) {
        String first = titleMode ? normalizeTitle(left) : normalize(left);
        String second = titleMode ? normalizeTitle(right) : normalize(right);
        if (first.isEmpty() || second.isEmpty()) return 0.0;
        if (first.equals(second)) return 1.0;
        if (first.contains(second) || second.contains(first)) {
            int shorter = Math.min(first.length(), second.length());
            int longer = Math.max(first.length(), second.length());
            return (double) shorter / (double) longer;
        }
        return jaccard(first, second);
    }

    private static double jaccard(String first, String second) {
        boolean[] inSecond = new boolean[Character.MAX_VALUE + 1];
        int secondSize = 0;
        for (int i = 0; i < second.length(); i++) {
            char character = second.charAt(i);
            if (inSecond[character]) continue;
            inSecond[character] = true;
            secondSize++;
        }

        boolean[] inFirst = new boolean[Character.MAX_VALUE + 1];
        int firstSize = 0;
        int intersection = 0;
        for (int i = 0; i < first.length(); i++) {
            char character = first.charAt(i);
            if (inFirst[character]) continue;
            inFirst[character] = true;
            firstSize++;
            if (inSecond[character]) intersection++;
        }

        int union = firstSize + secondSize - intersection;
        return union == 0 ? 0.0 : (double) intersection / (double) union;
    }

    // ── 歌手 ───────────────────────────────────────────────────────────────

    /**
     * 按连接符与 feat/ft 把歌手串拆成 token。注意 {@code featuring} 必须排在 {@code feat} 前面,
     * 否则 "featuring" 会被切成 "feat" + 残留的 "uring"。
     */
    private static final Pattern ARTIST_SEPARATOR = Pattern.compile(
            "(?iu)\\s*[,&、/／＆;；|·]\\s*|\\s+\\b(?:featuring|feat|ft)\\b\\.?\\s*");

    /** 拆出归一化后的歌手 token,空串与纯标点会被丢掉。 */
    static List<String> splitArtists(String artists) {
        List<String> tokens = new ArrayList<String>();
        if (artists == null || artists.trim().isEmpty()) return tokens;
        for (String part : ARTIST_SEPARATOR.split(artists)) {
            String normalized = normalize(part);
            if (!normalized.isEmpty()) tokens.add(normalized);
        }
        return tokens;
    }

    /**
     * 歌手相似度。先把两侧都拆成 token 集合逐对比较,再对<b>主歌手</b>(第一个 token)命中额外给
     * {@code 0.7} 的加成——跨平台的多歌手顺序与写法经常不一致,只看第一个会漏掉一堆正确的候选,
     * 而完全不看主歌手又会把翻唱版当原唱。
     *
     * @param targetArtists       本地歌曲的歌手串,可能是 {@code "A feat. B"} 这种
     * @param candidateArtistNames 候选方给出的歌手名列表
     */
    static double artistSimilarity(String targetArtists, List<String> candidateArtistNames) {
        String target = targetArtists == null ? "" : targetArtists.trim();
        if (target.isEmpty()) return 1.0;   // 本地拿不到歌手信息,无法判断,不因此否决候选

        List<String> targetTokens = splitArtists(target);
        if (targetTokens.isEmpty()) return 1.0;

        StringBuilder raw = new StringBuilder();
        List<String> candidateTokens = new ArrayList<String>();
        if (candidateArtistNames != null) {
            for (String name : candidateArtistNames) {
                if (name == null || name.trim().isEmpty()) continue;
                if (raw.length() > 0) raw.append(", ");
                raw.append(name.trim());
                candidateTokens.addAll(splitArtists(name));
            }
        }
        if (candidateTokens.isEmpty()) return 0.0;

        int matches = 0;
        for (String target1 : targetTokens) {
            for (String candidate : candidateTokens) {
                if (artistTokenMatches(target1, candidate)) {
                    matches++;
                    break;
                }
            }
        }

        double tokenSimilarity = (double) matches / (double) Math.max(targetTokens.size(), candidateTokens.size());
        double bonus = artistTokenMatches(targetTokens.get(0), candidateTokens.get(0))
                ? Math.max(tokenSimilarity, 0.7)
                : tokenSimilarity;
        return Math.max(bonus, similarity(target, raw.toString()));
    }

    private static boolean artistTokenMatches(String left, String right) {
        if (left.equals(right)) return true;
        // 短于 3 个字符的串互为子串没有意义("a" 会命中一切),所以只有够长时才算包含。
        if (left.length() >= 3 && right.contains(left)) return true;
        return right.length() >= 3 && left.contains(right);
    }

    // ── 检索词 ─────────────────────────────────────────────────────────────

    private static final int MAX_ALBUM_QUERY_LENGTH = 48;
    private static final Pattern ALBUM_NOTE_TAIL = Pattern.compile("(?iu)\\s*※.*$");
    private static final Pattern ALBUM_CREDIT_TAIL =
            Pattern.compile("(?iu)\\s*(?:歌|歌唱|アーティスト|artist)\\s*[:：]?\\s*.+$");

    /**
     * 构造 {@code 标题 - 歌手 - 专辑} 三段式检索词(缺哪段就少哪段)。仅供像 QQ 音乐这种
     * 「整串当关键词」的搜索接口使用——api.amll.dev 的 {@code musicName} 是严格标题匹配,
     * 加上歌手或专辑会直接返回 0 条,那边必须只传曲名。
     */
    static String buildStructuredQuery(String title, String artist, String album) {
        List<String> parts = new ArrayList<String>();
        addPart(parts, stripDuplicatedArtistPrefix(title, artist));
        addPart(parts, artist == null ? "" : artist.trim());
        addPart(parts, compactAlbum(album));

        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (builder.length() > 0) builder.append(" - ");
            builder.append(part);
        }
        return builder.toString();
    }

    /** 曲名形如 "歌手 - 曲名" 且前缀与歌手相同时,把重复的前缀剥掉,免得检索词里出现两次歌手。 */
    private static String stripDuplicatedArtistPrefix(String title, String artist) {
        String trimmedTitle = title == null ? "" : title.trim();
        String trimmedArtist = artist == null ? "" : artist.trim();
        if (trimmedTitle.isEmpty() || trimmedArtist.isEmpty()) return trimmedTitle;

        int separator = trimmedTitle.indexOf(" - ");
        if (separator <= 0) return trimmedTitle;
        String rest = trimmedTitle.substring(separator + 3).trim();
        if (rest.isEmpty()) return trimmedTitle;
        return normalize(trimmedTitle.substring(0, separator)).equals(normalize(trimmedArtist))
                ? rest
                : trimmedTitle;
    }

    /** 过长的专辑名(动画盘、合辑的全称常有几十字)先剥掉编目注释与演职员尾巴,再截断。 */
    private static String compactAlbum(String album) {
        String trimmed = album == null ? "" : album.trim();
        if (trimmed.isEmpty() || trimmed.length() <= MAX_ALBUM_QUERY_LENGTH) return trimmed;

        String compact = ALBUM_NOTE_TAIL.matcher(trimmed).replaceAll("").trim();
        compact = ALBUM_CREDIT_TAIL.matcher(compact).replaceAll("").trim();
        if (compact.length() <= MAX_ALBUM_QUERY_LENGTH) return compact;
        return truncate(compact, MAX_ALBUM_QUERY_LENGTH).trim();
    }

    /** 截断时不要把代理对(emoji、罕见汉字)劈成两半。 */
    private static String truncate(String value, int limit) {
        if (value.length() <= limit) return value;
        int end = limit;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }

    private static void addPart(List<String> parts, String part) {
        if (part == null) return;
        String trimmed = part.trim();
        if (!trimmed.isEmpty()) parts.add(trimmed);
    }

    static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** 取列表里第一个非空项,没有则返回空串。 */
    static String firstNonBlank(List<String> values) {
        if (values == null) return "";
        for (String value : values) {
            if (!isBlank(value)) return value.trim();
        }
        return "";
    }

    static String lowerCase(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
