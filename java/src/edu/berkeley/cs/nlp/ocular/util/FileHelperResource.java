package edu.berkeley.cs.nlp.ocular.util;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;

import org.exist.util.io.Resource;

/**
 * @author Dan Garrette (dhgarrette@gmail.com)
 */
public class FileHelperResource {

	public static void writeString(String path, String str) {
		BufferedWriter out = null;
		try {
			Resource f = new Resource(path);
			Resource parent = f.getParentFile();
			if (!parent.exists()) parent.mkdirs();
			out = (BufferedWriter) f.getWriter();
			out.write(str);
		} catch (IOException ex) {
			throw new RuntimeException(ex);
		} finally {
			if (out != null) {
				try { out.close(); } catch (Exception ex) {}
			}
		}
	}

}
