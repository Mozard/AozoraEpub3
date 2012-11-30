package com.github.hmdev.web;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Vector;
import java.util.regex.Pattern;

import org.apache.commons.compress.utils.IOUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import com.github.hmdev.util.CharUtils;
import com.github.hmdev.util.LogAppender;
import com.github.hmdev.web.ExtractInfo.ExtractId;

/** HTMLを青空txtに変換 */
public class WebAozoraConverter
{
	/** Singletonインスタンス格納 keyはFQDN */
	static HashMap<String, WebAozoraConverter> converters = new HashMap<String, WebAozoraConverter>();
	
	//設定ファイルから読み込むパラメータ
	/** リストページ抽出対象 HashMap<String key, String[]{cssQuery1, cssQuery2}> キーとJsoupのcssQuery(or配列) */
	HashMap<ExtractId, ExtractInfo[]> queryMap;
	
	/** 出力文字列置換情報 */
	HashMap<ExtractId, Vector<String[]>> replaceMap;
	
	/** テキスト出力先パス 末尾は/ */
	String dstPath;
	
	/** http?://fqdn/ の文字列 */
	String baseUri;
	
	/** 変換中のHTMLファイルのあるパス 末尾は/ */
	String pageBaseUri;
	
	boolean canceled = false;
	
	////////////////////////////////////////////////////////////////
	/** fqdnに対応したインスタンスを生成してキャッシュして変換実行 */
	public static WebAozoraConverter createWebAozoraConverter(String urlString, File configPath) throws IOException
	{
		String baseUri = urlString.substring(0, urlString.indexOf('/', urlString.indexOf("//")+2));
		String fqdn = baseUri.substring(baseUri.indexOf("//")+2);
		WebAozoraConverter converter = converters.get(fqdn);
		if (converter == null) {
			converter = new WebAozoraConverter(fqdn, configPath);
			if (!converter.isValid()) {
				LogAppender.println("サイトの定義がありません: "+configPath.getName()+"/"+fqdn);
				return null;
			}
			converters.put(fqdn, converter);
		}
		return converter;
		//return converter._convertToAozoraText(urlString, baseUri, fqdn, cachePath);
	}
	
	
	////////////////////////////////////////////////////////////////
	/** fqdnに対応したパラメータ取得 
	 * @throws IOException */
	WebAozoraConverter(String fqdn, File configPath) throws IOException
	{
		if (configPath.isDirectory()) {
			for (File file : configPath.listFiles()) {
				if (file.isDirectory() && file.getName().equals(fqdn)) {
					
					//抽出情報
					File extractInfoFile = new File(configPath.getAbsolutePath()+"/"+fqdn+"/extract.txt");
					if (!extractInfoFile.isFile()) return;
					this.queryMap = new HashMap<ExtractId, ExtractInfo[]>();
					String line;
					BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(extractInfoFile), "UTF-8"));
					try {
						while ((line = br.readLine()) != null) {
							if (line.length() == 0 || line.charAt(0) == '#') continue;
							String[] values = line.split("\t", -1);
							if (values.length > 1) {
								ExtractId extractId = ExtractId.valueOf(values[0]);
								String[] queryStrings = values[1].split(",");
								Pattern pattern = values.length > 2 ? Pattern.compile(values[2]) : null; //ExtractInfoが複数でも同じ値を設定
								String replaceValue = values.length > 3 ? values[3] : null; //ExtractInfoが複数でも同じ値を設定
								ExtractInfo[] extractInfos = new ExtractInfo[queryStrings.length];
								for (int i=0; i<queryStrings.length; i++) extractInfos[i] = new ExtractInfo(queryStrings[i], pattern, replaceValue);
								this.queryMap.put(extractId, extractInfos);
							}
						}
					} finally{
						br.close();
					}
					
					//置換情報
					this.replaceMap = new HashMap<ExtractId, Vector<String[]>>();
					File replaceInfoFile = new File(configPath.getAbsolutePath()+"/"+fqdn+"/replace.txt");
					if (replaceInfoFile.isFile()) {
						br = new BufferedReader(new InputStreamReader(new FileInputStream(replaceInfoFile), "UTF-8"));
						try {
							while ((line = br.readLine()) != null) {
								if (line.length() == 0 || line.charAt(0) == '#') continue;
								String[] values = line.split("\t");
								if (values.length > 1) {
									ExtractId extractId = ExtractId.valueOf(values[0]);
									Vector<String[]> vecReplace = this.replaceMap.get(extractId);
									if (vecReplace == null) {
										vecReplace = new Vector<String[]>();
										this.replaceMap.put(extractId, vecReplace);
									}
									vecReplace.add(new String[]{values[1], values.length==2?"":values[2]});
								}
							}
						} finally{
							br.close();
						}
					}
					return;
				}
			}
		}
	}
	
	////////////////////////////////////////////////////////////////
	private boolean isValid()
	{
		return this.queryMap != null;
	}
	
	public void canceled()
	{
		this.canceled = true;
	}
	public boolean isCanceled()
	{
		return this.canceled;
	}
	
	////////////////////////////////////////////////////////////////
	/** 変換実行 */
	public File convertToAozoraText(String urlString, File cachePath) throws IOException
	{
		this.canceled = false;
		this.baseUri = urlString.substring(0, urlString.indexOf('/', urlString.indexOf("//")+2));
		//String fqdn = baseUri.substring(baseUri.indexOf("//")+2);
		String listBaseUrl = urlString.substring(0, urlString.lastIndexOf('/')+1);
		this.pageBaseUri = listBaseUrl;
		//http://を除外
		String urlFilePath = CharUtils.escapeUrlToFile(urlString.substring(urlString.indexOf("//")+2));
		//http://を除外した文字列で比較
		/*ExtractInfo[] extractInfos = this.queryMap.get(ExtractId.PAGE_REGEX);
		if(extractInfos != null) {
			if (!extractInfos[0].matches(urlString)) {
				LogAppender.println("読み込み可能なURLではありません");
				return null;
			}
		}*/
		
		String urlParentPath = urlFilePath;
		boolean isPath = false;
		if (urlFilePath.endsWith("/")) { isPath = true; urlFilePath += "index.html"; }
		else urlParentPath = urlFilePath.substring(0, urlFilePath.lastIndexOf('/')+1);
		
		//変換結果
		this.dstPath = cachePath.getAbsolutePath()+"/";
		if (isPath) this.dstPath += urlParentPath;
		else this.dstPath += urlFilePath+"_converted/";
		File txtFile = new File(this.dstPath+"converted.txt");
		//更新情報格納先
		File updateInfoFile = new File(this.dstPath+"update.txt");
		
		txtFile.getParentFile().mkdirs();
		BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(txtFile), "UTF-8"));
		try {
			
			//urlStringのファイルをキャッシュ
			File cacheFile = new File(cachePath.getAbsolutePath()+"/"+urlFilePath);
			try {
				LogAppender.append(urlString);
				cacheFile(urlString, cacheFile);
				LogAppender.println(" : List Loaded.");
				try { Thread.sleep(500); } catch (InterruptedException e) { }
			} catch (Exception e) {
				e.printStackTrace();
				LogAppender.println("一覧ページの取得に失敗しました。 ");
				if (!cacheFile.exists()) return null;
				
				LogAppender.println("キャッシュファイルを利用します。");
			}
			
			//パスならlist.txtの情報を元にキャッシュ後に青空txt変換して改ページで繋げて出力
			Document doc = Jsoup.parse(cacheFile, null);
			
			//表紙画像
			Elements images = getExtractElements(doc, this.queryMap.get(ExtractId.COVER_IMG));
			if (images != null) printImage(bw, images.get(0));
			
			//タイトル
			boolean hasTitle = false;
			String series = getExtractText(doc, this.queryMap.get(ExtractId.SERIES));
			if (series != null) {
				printText(bw, series);
				bw.append('\n');
				hasTitle = true;
			}
			String title = getExtractText(doc, this.queryMap.get(ExtractId.TITLE));
			if (title != null) {
				printText(bw, title);
				bw.append('\n');
				hasTitle = true;
			}
			if (!hasTitle) {
				LogAppender.println("タイトルがありません");
				return null;
			}
			
			//著者
			String author = getExtractText(doc, this.queryMap.get(ExtractId.AUTHOR));
			if (author != null) {
				printText(bw, author);
			}
			bw.append('\n');
			//説明
			String description = getExtractText(doc, this.queryMap.get(ExtractId.DESCRIPTION));
			if (description != null) {
				bw.append('\n');
				bw.append("［＃区切り線］\n");
				bw.append('\n');
				bw.append("［＃ここから２字下げ］\n");
				bw.append("［＃ここから２字上げ］\n");
				printText(bw, description);
				bw.append('\n');
				bw.append("［＃ここで字上げ終わり］\n");
				bw.append("［＃ここで字下げ終わり］\n");
				bw.append('\n');
				bw.append("［＃区切り線］\n");
				bw.append('\n');
			}
			
			String contentsUpdate = getExtractText(doc, this.queryMap.get(ExtractId.UPDATE));
			
			//章名称 変わった場合に出力
			String preChapterTitle = "";
			
			//各話のURL(フルパス)を格納
			Vector<String> chapterHrefs = new Vector<String>();
			
			Elements hrefs = getExtractElements(doc, this.queryMap.get(ExtractId.HREF));
			
			//更新のない各話のURL(フルパス)を格納
			//nullならキャッシュ更新無しで、空ならすべて更新される
			HashSet<String> noUpdateUrls = null;
			
			if (hrefs == null) {
				//ページ番号取得
				String pageNumString = getExtractText(doc, this.queryMap.get(ExtractId.PAGE_NUM));
				int pageNum = -1;
				try { pageNum = Integer.parseInt(pageNumString); } catch (Exception e) {}
				Element pageUrlElement = getExtractFirstElement(doc, this.queryMap.get(ExtractId.PAGE_URL));
				if (pageNum > 0 && pageUrlElement != null) {
					ExtractInfo pageUrlExtractInfo = this.queryMap.get(ExtractId.PAGE_URL)[0];
					//リンク生成 1～ページ番号まで
					for (int i=1; i<=pageNum; i++) {
						String pageUrl = pageUrlElement.attr("href");
						if (pageUrl != null) {
							pageUrl = pageUrlExtractInfo.replace(pageUrl+"\t"+i);
							if (pageUrl != null) {
								if (!pageUrl.startsWith("http")) {
									if (pageUrl.charAt(0) == '/') pageUrl = baseUri+pageUrl;
									else pageUrl = listBaseUrl+pageUrl;
								}
								chapterHrefs.add(pageUrl);
							}
						}
					}
				} else {
					Elements contentDivs = getExtractElements(doc, this.queryMap.get(ExtractId.CONTENT_ARTICLE));
					if (contentDivs != null) {
						//一覧のリンクはないが本文がある場合
						docToAozoraText(bw, doc, false);
					} else {
						LogAppender.println("一覧のリンク先が取得できませんでした");
						return null;
					}
				}
			} else {
				//更新分のみ取得するようにするためhrefに対応した日付タグの文字列(innerHTML)を取得して保存しておく
				Elements updates = getExtractElements(doc, this.queryMap.get(ExtractId.SUB_UPDATE));
				if (updates != null) {
					//更新情報
					noUpdateUrls = createNoUpdateUrls(updateInfoFile, urlString, listBaseUrl, contentsUpdate, hrefs, updates);
				}
				for (Element href : hrefs) {
					String hrefString = href.attr("href");
					if (hrefString == null || hrefString.length() == 0) continue;
					String chapterHref = hrefString;
					if (!hrefString.startsWith("http")) {
						if (hrefString.charAt(0) == '/') chapterHref = baseUri+hrefString;
						else chapterHref = listBaseUrl+hrefString;
					}
					chapterHrefs.add(chapterHref);
				}
			}
			
			if (chapterHrefs.size() > 0) {
				//全話で更新や追加があるかチェック
				boolean updated = false;
				
				for (String chapterHref : chapterHrefs) {
					if (this.canceled) return null;
					
					//キャッシュを再読込するならtrue
					boolean reload = false;
					//nullでなく含まれなければキャッシュ再読込
					if (noUpdateUrls != null && !noUpdateUrls.contains(chapterHref)) reload = true;
					
					if (chapterHref != null && chapterHref.length() > 0) {
						//画像srcをフルパスにするときに使うページのパス
						this.pageBaseUri = chapterHref;
						if (!chapterHref.endsWith("/")) {
							int idx = chapterHref.indexOf('/', 7);
							if (idx > -1) this.pageBaseUri = chapterHref.substring(0, idx);
						}
						
						//キャッシュ取得 ロードされたらWait 500ms
						String chapterPath = CharUtils.escapeUrlToFile(chapterHref.substring(chapterHref.indexOf("//")+2));
						File chapterCacheFile = new File(cachePath.getAbsolutePath()+"/"+chapterPath+(chapterPath.endsWith("/")?"index.html":""));
						//hrefsのときは更新分のみurlsに入っている
						if (reload || !chapterCacheFile.exists()) {
							LogAppender.append(chapterHref);
							try {
								cacheFile(chapterHref, chapterCacheFile);
								LogAppender.println(" : Loaded.");
								try { Thread.sleep(500); } catch (InterruptedException e) { }
								//ファイルがロードされたら更新有り
								updated = true;
							} catch (Exception e) {
								e.printStackTrace();
								LogAppender.println("htmlが取得できませんでした : "+chapterHref);
							}
						}
						
						//シリーズタイトルを出力
						Document chapterDoc = Jsoup.parse(chapterCacheFile, null);
						String chapterTitle = getExtractText(chapterDoc, this.queryMap.get(ExtractId.CONTENT_CHAPTER));
						boolean newChapter = false;
						if (chapterTitle != null && !preChapterTitle.equals(chapterTitle)) {
							newChapter = true;
							preChapterTitle = chapterTitle;
							bw.append("［＃改ページ］\n");
							bw.append("［＃ここから大見出し］\n");
							printText(bw, preChapterTitle);
							bw.append('\n');
							bw.append("［＃ここで大見出し終わり］\n");
							bw.append('\n');
						}
						docToAozoraText(bw, chapterDoc, newChapter);
					}
				}
				if (!updated) {
					LogAppender.append(title);
					LogAppender.println(" の更新はありません");
				}
			}
			
		} finally {
			bw.close();
		}
		
		this.canceled = false;
		return txtFile;
	}
	
	/** 更新情報の生成と保存 */
	private HashSet<String> createNoUpdateUrls(File updateInfoFile, String urlString, String listBaseUrl, String contentsUpdate, Elements hrefs, Elements updates) throws IOException
	{
		HashMap<String, String> updateStringMap = new HashMap<String, String>();
		
		if (hrefs == null || updates == null || hrefs.size() != updates.size()) {
			
			return null;
			
		} else {
			if (updateInfoFile.exists()) {
				//前回の更新情報を取得して比較
				BufferedReader updateBr = new BufferedReader(new InputStreamReader(new FileInputStream(updateInfoFile), "UTF-8"));
				try {
					String line;
					while ((line=updateBr.readLine()) != null) {
						int idx = line.indexOf("\t");
						if (idx > 0) {
							updateStringMap.put(line.substring(0, idx), line.substring(idx+1));
						}
					}
					
				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					updateBr.close();
				}
			}
		}
		
		if (contentsUpdate != null || updates != null) {
			//ファイルに出力
			BufferedWriter updateBw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(updateInfoFile), "UTF-8"));
			try {
				if (contentsUpdate != null) {
					updateBw.append(urlString);
					updateBw.append('\t');
					updateBw.append(contentsUpdate);
					updateBw.append('\n');
				}
				if (updates != null) {
					int i = 0;
					for (Element update : updates) {
						updateBw.append(hrefs.get(i++).attr("href"));
						updateBw.append('\t');
						updateBw.append(update.html());
						updateBw.append('\n');
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			} finally {
				updateBw.close();
			}
		}
		
		HashSet<String> noUpdateUrls = new HashSet<String>();
		int i = -1;
		for (Element href : hrefs) {
			i++;
			String hrefString = href.attr("href");
			if (hrefString == null || hrefString.length() == 0) continue;
			String updateString = updateStringMap.get(hrefString);
			if (updateString != null && updateString.equals(updates.get(i).html())) {
				String chapterHref = hrefString;
				if (!hrefString.startsWith("http")) {
					if (hrefString.charAt(0) == '/') chapterHref = baseUri+hrefString;
					else chapterHref = listBaseUrl+hrefString;
				}
				noUpdateUrls.add(chapterHref);
			}
		}
		
		
		return noUpdateUrls;
	}
	
	/** 各話のHTMLの変換 */
	private void docToAozoraText(BufferedWriter bw, Document doc, boolean newChapter) throws IOException
	{
		Elements contentDivs = getExtractElements(doc, this.queryMap.get(ExtractId.CONTENT_ARTICLE));
		if (contentDivs != null && contentDivs.size() > 0) {
			if (!newChapter) bw.append("［＃改ページ］\n");
			String subTitle = getExtractText(doc, this.queryMap.get(ExtractId.CONTENT_SUBTITLE));
			if (subTitle != null) {
				bw.append("［＃ここから中見出し］\n");
				printText(bw, subTitle);
				bw.append('\n');
				bw.append("［＃ここで中見出し終わり］\n\n");
			}
			
			//画像 取り合えず前に
			Elements images = getExtractElements(doc, this.queryMap.get(ExtractId.CONTENT_IMG));
			if (images != null) printImage(bw, images.get(0));
			
			//前書き
			Elements preambleDivs = getExtractElements(doc, this.queryMap.get(ExtractId.CONTENT_PREAMBLE));
			if (preambleDivs != null) {
				bw.append("［＃区切り線］\n");
				bw.append("［＃ここから２字下げ］\n");
				bw.append("［＃ここから２字上げ］\n");
				bw.append("［＃ここから１段階小さな文字］\n");
				bw.append('\n');
				for (Element elem : preambleDivs) printNode(bw, elem);
				bw.append('\n');
				bw.append('\n');
				bw.append("［＃ここで小さな文字終わり］\n");
				bw.append("［＃ここで字上げ終わり］\n");
				bw.append("［＃ここで字下げ終わり］\n");
				bw.append("［＃区切り線］\n");
				bw.append('\n');
			}
			//本文
			for (Element elem : contentDivs) printNode(bw, elem);
			
			//後書き
			Elements appendixDivs = getExtractElements(doc, this.queryMap.get(ExtractId.CONTENT_APPENDIX));
			if (appendixDivs != null) {
				bw.append('\n');
				bw.append('\n');
				bw.append("［＃区切り線］\n");
				bw.append("［＃ここから２字下げ］\n");
				bw.append("［＃ここから２字上げ］\n");
				bw.append("［＃ここから１段階小さな文字］\n");
				bw.append('\n');
				for (Element elem : appendixDivs) printNode(bw, elem);
				bw.append('\n');
				bw.append('\n');
				bw.append("［＃ここで小さな文字終わり］\n");
				bw.append("［＃ここで字上げ終わり］\n");
				bw.append("［＃ここで字下げ終わり］\n");
			}
		}
	}
	/** ノードを出力 子ノード内のテキストも出力 */
	private void printNode(BufferedWriter bw, Node parent) throws IOException
	{
		for (Node node : parent.childNodes()) {
			if (node instanceof TextNode) printText(bw, ((TextNode)node).text());
			else if (node instanceof Element) {
				Element elem = (Element)node;
				if ("br".equals(elem.tagName())) {
					if (elem.nextSibling() != null) bw.append('\n');
				} else if ("div".equals(elem.tagName())) {
					printNode(bw, node); //子を出力
					if (elem.nextSibling() != null) bw.append('\n');
				} else if ("p".equals(elem.tagName())) {
					printNode(bw, node); //子を出力
					if (elem.nextSibling() != null) bw.append('\n');
				} else if ("ruby".equals(elem.tagName())) {
					//ルビ注記出力
					printRuby(bw, elem);
				} else if ("img".equals(elem.tagName())) {
					//画像をキャッシュして注記出力
					printImage(bw, elem);
				} else if ("hr".equals(elem.tagName())) {
					bw.append("［＃区切り線］\n");
				} else if ("b".equals(elem.tagName())) {
					bw.append("［＃ここから太字］");
					printNode(bw, node); //子を出力
					bw.append("［＃ここで太字終わり］");
				} else if ("sup".equals(elem.tagName())) {
					bw.append("［＃上付き小文字］");
					printNode(bw, node); //子を出力
					bw.append("［＃上付き小文字終わり］");
				} else if ("sub".equals(elem.tagName())) {
					bw.append("［＃下付き小文字］");
					printNode(bw, node); //子を出力
					bw.append("［＃下付き小文字終わり］");
				} else if ("strike".equals(elem.tagName())) {
					bw.append("［＃取消線］");
					printNode(bw, node); //子を出力
					bw.append("［＃取消線終わり］");
				} else if ("tr".equals(elem.tagName())) {
					printNode(bw, node); //子を出力
					bw.append('\n');
				} else {
					printNode(bw, node); //子を出力
				}
			} else {
				System.out.println(node.getClass().getName());
			}
		}
	}
	/** ルビを青空ルビにして出力 */
	private void printRuby(BufferedWriter bw, Element ruby) throws IOException
	{
		Elements rb = ruby.getElementsByTag("rb");
		Elements rt = ruby.getElementsByTag("rt");
		if (rb.size() > 0) {
			if (rt.size() > 0) {
				bw.append('｜');
				bw.append(rb.get(0).text());
				bw.append('《');
				bw.append(rt.get(0).text());
				bw.append('》');
			} else {
				bw.append(rb.get(0).text());
			}
		}
	}
	/** 画像をキャッシュして相対パスの注記にする */
	private void printImage(BufferedWriter bw, Element img) throws IOException
	{
		String src = img.attr("src");
		if (src == null || src.length() == 0) return;
		
		String imagePath = null;
		int idx = src.indexOf("//");
		if (idx > 0) {
			imagePath = CharUtils.escapeUrlToFile(src.substring(idx+2));
		} else if (src.charAt(0) == '/') {
			imagePath = "_"+CharUtils.escapeUrlToFile(src);
			src = this.baseUri+src;
		}
		else {
			imagePath = "__/"+CharUtils.escapeUrlToFile(src);
			src = this.pageBaseUri+"/"+src;
		}
		
		if (imagePath.endsWith("/")) imagePath += "image.png";
		
		File imageFile = new File(this.dstPath+"images/"+imagePath);
		if (!imageFile.exists()) {
			try {
				cacheFile(src, imageFile);
			} catch (Exception e) {
				e.printStackTrace();
				LogAppender.println("画像が取得できませんでした : "+src);
			}
		}
		bw.append("［＃挿絵（");
		bw.append("images/"+imagePath);
		bw.append("）入る］\n");
	}
	
	/** 文字を出力 特殊文字は注記に変換 */
	private void printText(BufferedWriter bw, String text) throws IOException
	{
		char[] chars = text.toCharArray();
		for (char ch : chars) {
			//青空特殊文字
			switch (ch) {
			case '《': bw.append("※［＃始め二重山括弧、1-1-52］"); break;
			case '》': bw.append("※［＃終わり二重山括弧、1-1-53］"); break;
			case '［': bw.append("※［＃始め角括弧、1-1-46］"); break;
			case '］': bw.append("※［＃終わり角括弧、1-1-47］"); break;
			case '〔': bw.append("※［＃始め亀甲括弧、1-1-44］"); break;
			case '〕': bw.append("※［＃終わり亀甲括弧、1-1-45］"); break;
			case '｜': bw.append("※［＃縦線、1-1-35］"); break;
			case '＃': bw.append("※［＃井げた、1-1-84］"); break;
			case '※': bw.append("※［＃米印、1-2-8］"); break;
			default: bw.append(ch); 
			}
		}
	}
	
	////////////////////////////////////////////////////////////////
	
	/** Element内の文字列を取得
	 * 間にあるダグは無視 置換設定があれば置換してから返す */
	String getExtractText(Document doc, ExtractInfo[] extractInfos)
	{
		return getExtractText(doc, extractInfos, true);
	}
	String getExtractText(Document doc, ExtractInfo[] extractInfos, boolean replace)
	{
		if (extractInfos == null) return null;
		for (ExtractInfo extractInfo : extractInfos) {
			String text = null;
			Elements elements = doc.select(extractInfo.query);
			StringBuilder buf = new StringBuilder();
			if (extractInfo.idx == null) {
				for (Element element : elements) {
					buf.append(" ").append(getFirstText(element));
				}
			} else {
				for (int i=0; i<extractInfo.idx.length; i++) {
					if (elements.size() > extractInfo.idx[i]) {
						String firstText = getFirstText(elements.get(extractInfo.idx[i]));
						if (firstText != null) buf.append(" ").append(firstText);
					}
				}
				if (buf.length() > 0) text = buf.deleteCharAt(0).toString();
			}
			//置換指定ならreplaceして返す
			if (text != null && text.length() > 0) {
				if (replace) return extractInfo.replace(text);
				else return text;
			}
		}
		return null;
	}
	
	/** cssQueryに対応するノード内の文字列を取得 */
	String getQueryText(Document doc, String[] queries)
	{
		if (queries == null) return null;
		for (String query : queries) {
			String text  = getFirstText(doc.select(query).first());
			if (text != null && text.length() > 0) return text;
		}
		return null;
	}
	
	/** cssQueryに対応するノードを取得 前のQueryを優先 */
	Elements getExtractElements(Document doc, ExtractInfo[] extractInfos)
	{
		if (extractInfos == null) return null;
		for (ExtractInfo extractInfo : extractInfos) {
			Elements elements = doc.select(extractInfo.query);
			if (elements == null || elements.size() == 0) continue;
			if (extractInfo.idx == null) return elements;
			Elements e2 = new Elements();
			for (int i=0; i<extractInfo.idx.length; i++) {
				int pos = extractInfo.idx[i];
				if (pos < 0) pos = elements.size()+pos;//負の値なら後ろから
				if (pos >= 0 && elements.size() > pos) e2.add(elements.get(pos));
			}
			if (e2.size() >0) return e2;
		}
		return null;
	}
	
	/** cssQueryに対応するノードを取得 前のQueryを優先 */
	Element getExtractFirstElement(Document doc, ExtractInfo[] extractInfos)
	{
		if (extractInfos == null) return null;
		for (ExtractInfo extractInfo : extractInfos) {
			Elements elements = doc.select(extractInfo.query);
			if (elements == null || elements.size() == 0) return null;
			return elements.get(0);
		}
		return null;
	}
	
	/** タグの直下の最初のテキストを取得 */
	String getFirstText(Element elem)
	{
		if (elem != null) {
			List<TextNode> nodes = elem.textNodes();
			for (Node node : nodes) {
				if (node instanceof TextNode) {
					String text = ((TextNode) node).text().trim();
					if (text != null && text.length() > 0) {
						return text;
					}
				}
			}
		}
		return null;
	}
	
	////////////////////////////////////////////////////////////////
	/** htmlをキャッシュ すでにあれば何もしない */
	private boolean cacheFile(String urlString, File cacheFile) throws IOException
	{
		//if (!replace && cacheFile.exists()) return false;
		cacheFile.getParentFile().mkdirs();
		//ダウンロード
		URLConnection conn = new URL(urlString).openConnection();
		conn.setConnectTimeout(5000);//5秒
		BufferedInputStream bis = new BufferedInputStream(conn.getInputStream(), 8192);
		BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(cacheFile));
		IOUtils.copy(bis, bos);
		bos.close();
		bis.close();
		return true;
	}
}
