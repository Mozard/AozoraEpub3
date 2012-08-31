/**
 * 改ページ情報
 */
package com.github.hmdev.converter;

public class PageBreakTrigger
{
	/** 空のページは無視する */
	boolean ignoreEmptyPage = true;
	/** 次のページは左右中央 */
	boolean isMiddle = false;
	/** 次のページは画像 */
	boolean isImage = false;
	
	/** 目次に出力しない */
	boolean noChapter = false;
	
	/** 画像単一ページの場合の画像ファイル名 */
	String imageFileName = null;
	
	/**
	 * @param ignoreEmptyPage
	 * @param isMiddle
	 * @param isImage 	 */
	public PageBreakTrigger(boolean ignoreEmptyPage, boolean isMiddle, boolean isImage)
	{
		this(ignoreEmptyPage, isMiddle, isImage, false);
	}
	public PageBreakTrigger(boolean ignoreEmptyPage, boolean isMiddle, boolean isImage, boolean noChapter)
	{
		super();
		this.ignoreEmptyPage = ignoreEmptyPage;
		this.isMiddle = isMiddle;
		this.isImage = isImage;
		this.noChapter = noChapter;
	}
}
