package edu.berkeley.cs.nlp.ocular.data;

import java.awt.image.BufferedImage;
import java.io.File;

import edu.berkeley.cs.nlp.ocular.util.FileUtil;
import fileio.fResource;

/**
 * A document that reads a file only as it is needed (and then stores
 * the contents in memory for later use).
 * 
 * @author Dan Garrette (dhgarrette@gmail.com)
 */
public class LazyRawSingleImageDocumentResource extends LazyRawImageDocumentResource {
	private final File file;

	public LazyRawSingleImageDocumentResource(File file, String inputPath, int lineHeight, double binarizeThreshold, boolean crop, String extractedLinesPath, int numExtractIterations, int numExtractRestarts, int extractLineHeight) {
		super(inputPath, lineHeight, binarizeThreshold, crop, extractedLinesPath, numExtractIterations, numExtractRestarts, extractLineHeight);
		this.file = file;
	}

	protected BufferedImage doLoadBufferedImage() {
		System.out.println("Extracting text line images from " + file);
		return fResource.readImage(file.getPath());
  }
	
	protected File file() { return file; }
	protected String preext() { return FileUtil.withoutExtension(file.getName()); }
	protected String ext() { return FileUtil.extension(file.getName()); }

	public String baseName() {
		return file.getPath();
	}

}
