/*******************************************************************************
 * Copyright (c) 2006 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.osgi.internal.verifier;

import java.io.*;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.eclipse.osgi.baseadaptor.bundlefile.*;
import org.eclipse.osgi.framework.log.FrameworkLogEntry;
import org.eclipse.osgi.internal.provisional.verifier.CertificateChain;
import org.eclipse.osgi.internal.provisional.verifier.CertificateVerifier;

/**
 * This class wraps a Repository of classes and resources to check and enforce
 * signatures. It requires full signing of the manifest by all signers. If no
 * signatures are found, the classes and resources are retrieved without checks.
 */
public class SignedBundleFile extends BundleFile implements CertificateVerifier {
	/**
	 * A precomputed MD5 MessageDigest. We will clone this everytime we want to
	 * use it.
	 */
	static MessageDigest md5;
	/**
	 * A precomputed SHA1 MessageDigest. We will clone this everytime we want to
	 * use it.
	 */
	static MessageDigest sha1;
	static {
		try {
			md5 = MessageDigest.getInstance("MD5"); //$NON-NLS-1$
		} catch (NoSuchAlgorithmException e) {
			// TODO Log this somewhere
			e.printStackTrace();
		}
		try {
			sha1 = MessageDigest.getInstance("SHA1"); //$NON-NLS-1$
		} catch (NoSuchAlgorithmException e1) {
			// TODO Log this somewhere
			e1.printStackTrace();
		}
	}
	//
	// following are variables and methods to cache the entries related data
	// for a given MF file
	//
	private static final String MF_ENTRY_NEWLN_NAME = "\nName: "; //$NON-NLS-1$
	private static final String MF_ENTRY_NAME = "Name: "; //$NON-NLS-1$
	private static final String MF_DIGEST_PART = "-Digest: "; //$NON-NLS-1$
	private static final String digestManifestSearch = "-Digest-Manifest: "; //$NON-NLS-1$
	private static final int digestManifestSearchLen = digestManifestSearch.length();
	private static final String[] EMPTY_STRING = new String[0];

	private BundleFile bundleFile;
	CertificateChain[] chains;

	/**
	 * The key of the hashtable will be the name of the entry (type String). The
	 * value will be MessageDigest[] to use. Before using the MessageDigests
	 * must be cloned.
	 */
	Hashtable digests4entries;
	/**
	 * The key of the hashtable will be the name of the entry (type String). The
	 * value will be byte[][] which is an array of MessageDigest results. Each
	 * result in the array will correspond to the MessageDigest at the same
	 * position for the same entry in digests4entries.
	 */
	Hashtable results4entries;
	String manifestSHAResult = null;
	String manifestMD5Result = null;
	boolean certsInitialized = false;

	SignedBundleFile() {
		// default constructor
	}

	SignedBundleFile(CertificateChain[] chains, Hashtable digests4entries, Hashtable results4entries, String manifestMD5Result, String manifestSHAResult) {
		this.chains = chains;
		this.digests4entries = digests4entries;
		this.results4entries = results4entries;
		this.manifestMD5Result = manifestMD5Result;
		this.manifestSHAResult = manifestSHAResult;
		certsInitialized = true;
	}

	/**
	 * @param mfBuf the data from an MF file of a JAR archive
	 * 
	 * This method will populate the "digest type & result" hashtables 
	 * with whatever entries it can correctly parse from the MF file
	 * it will 'skip' incorrect entries (TODO: should the correct behavior
	 * be to throw an exception, or return an error code?)...
	 */
	private void populateManifest(byte mfBuf[]) {
		// need to make a string from the MF file data bytes
		String mfStr = new String(mfBuf);

		// start parsing each entry in the MF String
		int entryStartOffset = mfStr.indexOf(MF_ENTRY_NEWLN_NAME);

		while ((entryStartOffset != -1) && (entryStartOffset < mfStr.length())) {

			// get the start of the next 'entry', i.e. the end of this entry
			int entryEndOffset = mfStr.indexOf(MF_ENTRY_NEWLN_NAME, entryStartOffset + 1);
			if (entryEndOffset == -1) {
				// if there is no next entry, then the end of the string
				// is the end of this entry
				entryEndOffset = mfStr.length();
			}

			// get the string for this entry only, since the entryStartOffset
			// points to the '\n' befor the 'Name: ' we increase it by 1
			// this is guaranteed to not go past end-of-string and be less
			// then entryEndOffset.
			String entryStr = mfStr.substring(entryStartOffset + 1, entryEndOffset);
			entryStr = stripContinuations(entryStr);

			// entry points to the start of the next 'entry'
			String entryName = getName(entryStr);

			// if we could retrieve an entry name, then we will extract
			// digest type list, and the digest value list
			if (entryName != null) {

				String digestLines[] = getDigestLines(entryStr);

				if (digestLines != null) {
					MessageDigest digestList[] = getDigestList(digestLines);
					byte digestResultsList[][] = getDigestResultsList(digestLines);

					//
					// only insert this entry into the table if its
					// "well-formed",
					// i.e. only if we could extract its name, digest types, and
					// digest-results
					//
					// sanity check, if the 2 lists are non-null, then their
					// counts must match
					//
					if ((digestList != null) && (digestResultsList != null) && (digestList.length != digestResultsList.length)) {
						throw new RuntimeException("digest and digest results were different counts.."); //$NON-NLS-1$
					}
					// see if we should insert this entry
					if ((entryName != null) && (digestList != null) && (digestResultsList != null)) {
						if (digests4entries == null) {
							digests4entries = new Hashtable(10);
							results4entries = new Hashtable(10);
						}
						// TODO throw exception if duplicate entry??
						if (!digests4entries.contains(entryName)) {
							digests4entries.put(entryName, digestList);
							results4entries.put(entryName, digestResultsList);
						}
					} // could retrieve entry-name, digest list, and results list

				} // could get lines of digest entries in this MF file entry

			} // could retrieve entry name

			// increment the offset to the ending entry...
			entryStartOffset = entryEndOffset;
		}
	}

	private String stripContinuations(String entry) {
		if (entry.indexOf("\n ") < 0) //$NON-NLS-1$
			return entry;
		StringBuffer buffer = new StringBuffer(entry.length());
		int cont = entry.indexOf("\n "); //$NON-NLS-1$
		int start = 0;
		while (cont >= 0) {
			buffer.append(entry.substring(start, cont - 1));
			start = cont + 2;
			cont = cont + 2 < entry.length() ? entry.indexOf("\n ", cont + 2) : -1; //$NON-NLS-1$
		}
		// get the last one continuation
		if (start < entry.length())
			buffer.append(entry.substring(start));
		return buffer.toString();
	}

	private String getName(String manifestEntry) {
		// get the beginning of the name
		int nameStart = manifestEntry.indexOf(MF_ENTRY_NAME);
		if (nameStart == -1) {
			return null;
		}
		// check where the name ends
		int nameEnd = manifestEntry.indexOf('\n', nameStart);
		if (nameEnd == -1) {
			return null;
		}
		// if there is a '\r' before the '\n', then we'll strip it
		if (manifestEntry.charAt(nameEnd - 1) == '\r') {
			nameEnd--;
		}
		// get to the beginning of the actual name...
		nameStart += MF_ENTRY_NAME.length();
		if (nameStart >= nameEnd) {
			return null;
		}
		return manifestEntry.substring(nameStart, nameEnd);
	}

	/**
	 * 
	 * @param manifestEntry contains a single MF file entry of the format
	 * 				   "Name: foo"
	 * 				   "MD5-Digest: [base64 encoded MD5 digest data]"
	 * 				   "SHA1-Digest: [base64 encoded SHA1 digest dat]"
	 * 
	 * @return this function returns an array of strings for each
	 *         recognized digest entry which will at most have 2 entries 
	 * 		   (since only MD5 and SHA1 are recognized here),
	 * 		   or a 'null' will be returned if none of the digest algorithms
	 * 		   were recognized, or more then 2 valid lines are found
	 */
	private String[] getDigestLines(String manifestEntry) {

		// this is the common case, that we will return 1 string
		// for a single digest algorithm that we recognize
		String digestLines[] = new String[1];
		// keeps track of how many valid digest lines we found
		int numFound = 0;

		// find the first digest line
		int indexDigest = manifestEntry.indexOf(MF_DIGEST_PART);
		// if we didn't find any digests at all, then we are done
		if (indexDigest == -1)
			return null;

		// while we continue to find digest entries
		// note: in the following loop we bail if any of the lines
		//		 look malformed...
		while (indexDigest != -1) {
			// see where this digest line begins (look to left)
			int indexStart = manifestEntry.lastIndexOf('\n', indexDigest);
			if (indexStart == -1)
				return null;
			// see where it ends (look to right)
			int indexEnd = manifestEntry.indexOf('\n', indexDigest);
			if (indexEnd == -1)
				return null;
			// strip off ending '\r', if any
			int indexEndToUse = indexEnd;
			if (manifestEntry.charAt(indexEndToUse - 1) == '\r')
				indexEndToUse--;
			// indexStart points to the '\n' before this digest line
			int indexStartToUse = indexStart + 1;
			if (indexStartToUse >= indexEndToUse)
				return null;

			// now this may be a valid digest line, parse it a bit more
			// to see if this is a preferred digest algorithm
			String digestLine = manifestEntry.substring(indexStartToUse, indexEndToUse);
			if (digestLine.startsWith("MD5") || digestLine.startsWith("SHA1")) { //$NON-NLS-1$ //$NON-NLS-2$
				// if we are here, then we will attempt to keep
				// track of this digest line
				numFound++;

				if (numFound == 2) {
					// SPECIAL CASE: if 2 lines are found
					// then we grow the digestLines array manually
					String tempDigestLines[] = digestLines;
					digestLines = new String[2];
					digestLines[0] = tempDigestLines[0];
					digestLines[1] = digestLine;
				} else if (numFound == 1) {
					// SPECIAL CASE: if this is the first line found
					// then we already have a pre-allocated array for this
					digestLines[0] = digestLine;
				} else {
					// problem..., we found more then 2 valid
					// digest lines
					// TODO: (note that we can be more strict here and check
					//        to ensure that we didn't find 2 MD5's or 2 SHA1 lines)
					return null;
				}
			} // if digest algorithm was MD5 or SHA1

			// iterate to next digest line in this entry
			indexDigest = manifestEntry.indexOf(MF_DIGEST_PART, indexEnd);
		}

		// if we couldn't find any digest lines, then we are done
		return numFound == 0 ? null : digestLines;
	}

	private MessageDigest[] getDigestList(String digestLines[]) {

		MessageDigest mdList[] = new MessageDigest[digestLines.length];

		// for each digest-line retrieve the digest algorithm
		for (int i = 0; i < digestLines.length; i++) {
			String sDigestLine = digestLines[i];
			int indexDigest = sDigestLine.indexOf(MF_DIGEST_PART);
			String sDigestAlgType = sDigestLine.substring(0, indexDigest);
			if (sDigestAlgType.equals("MD5")) { //$NON-NLS-1$
				// remember the "algorithm type" object
				mdList[i] = md5;
			} else if (sDigestAlgType.equals("SHA1")) { //$NON-NLS-1$
				// remember the "algorithm type" object
				mdList[i] = sha1;
			} else {
				// unknown algorithm type, we will stop processing this entry
				mdList = null;
				break;
			}
		}
		return mdList;
	}

	private byte[][] getDigestResultsList(String digestLines[]) {
		byte resultsList[][] = new byte[digestLines.length][];
		// for each digest-line retrieve the digest result
		for (int i = 0; i < digestLines.length; i++) {
			String sDigestLine = digestLines[i];
			int indexDigest = sDigestLine.indexOf(MF_DIGEST_PART);
			indexDigest += MF_DIGEST_PART.length();
			// if there is no data to extract for this digest value
			// then we will fail...
			if (indexDigest >= sDigestLine.length()) {
				resultsList = null;
				break;
			}
			// now attempt to base64 decode the result
			String sResult = sDigestLine.substring(indexDigest);
			try {
				resultsList[i] = Base64.decode(sResult.getBytes());
			} catch (Throwable t) {
				// malformed digest result, no longer processing this entry
				resultsList = null;
				break;
			}
		}
		return resultsList;
	}

	static private int readFully(InputStream is, byte b[]) throws IOException {
		int count = b.length;
		int offset = 0;
		int rc;
		while ((rc = is.read(b, offset, count)) > 0) {
			count -= rc;
			offset += rc;
		}
		return offset;
	}

	byte[] readIntoArray(BundleEntry be) throws IOException {
		int size = (int) be.getSize();
		InputStream is = be.getInputStream();
		byte b[] = new byte[size];
		int rc = readFully(is, b);
		if (rc != size) {
			throw new IOException("Couldn't read all of " + be.getName() + ": " + rc + " != " + size); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		return b;
	}

	/**
	 * Sets the BundleFile for this singed bundle. It will extract
	 * signatures and digests from the bundle file and validate input streams
	 * before using them from the bundle file.
	 * 
	 * @param bundleFile the BundleFile to extract elements from.
	 * @throws IOException
	 */
	void setBundleFile(BundleFile bundleFile) throws IOException {
		this.bundleFile = bundleFile;
		if (certsInitialized)
			return;
		ArrayList chainList = new ArrayList();
		BundleEntry be = bundleFile.getEntry("META-INF/MANIFEST.MF"); //$NON-NLS-1$
		if (be == null) {
			return;
		}
		byte manifestBytes[] = readIntoArray(be);
		Enumeration en = bundleFile.getEntryPaths("META-INF/"); //$NON-NLS-1$
		while (en.hasMoreElements()) {
			String name = (String) en.nextElement();
			if ((name.endsWith(".DSA") || name.endsWith(".RSA")) && name.indexOf('/') == name.lastIndexOf('/')) { //$NON-NLS-1$ //$NON-NLS-2$
				be = bundleFile.getEntry(name);
				byte pkcs7Bytes[] = readIntoArray(be);
				int dotIndex = name.lastIndexOf('.');
				be = bundleFile.getEntry(name.substring(0, dotIndex) + ".SF"); //$NON-NLS-1$
				byte sfBytes[] = readIntoArray(be);
				// Get the manifest out of the signature file and make sure
				// it matches MANIFEST.MF
				if (!checkManifestDigest(manifestBytes, sfBytes)) {
					// We only recognize signatures over the Manifest
					// XXX should log something here
					continue;
				}
				// Now that the manifest digest checks out, check the signature
				PKCS7Processor chain = null;
				try {
					chain = new PKCS7Processor(pkcs7Bytes, 0, pkcs7Bytes.length, sfBytes, 0, sfBytes.length);
				} catch (Exception e) {
					// For any problems we just bag this signature and move on
					SignedBundleHook.log("Invalid or untrusted certificate: " + bundleFile.getBaseFile(), FrameworkLogEntry.WARNING, e); //$NON-NLS-1$
					continue;
				}
				if (chain != null && chain.getSigner() != null)
					chainList.add(chain);
			}
		}
		chains = chainList.size() == 0 ? null : (CertificateChain[]) chainList.toArray(new CertificateChain[chainList.size()]);
		if (chains != null)
			populateManifest(manifestBytes);
	}

	/**
	 * Check the Manifest digests in a signature file. It only returns true if
	 * there is a digest for the manifest and the digest matches the actual
	 * digest of the manifest.
	 * 
	 * @param manifestBytes the bytes that make up the real manifest file.
	 * @param sfBytes the bytes that make up the signature file.
	 * @return true if the signature file has a manifest digest entry that
	 *         matches the real manifest file.
	 */
	private boolean checkManifestDigest(byte[] manifestBytes, byte[] sfBytes) {
		String sf = new String(sfBytes);
		sf = stripContinuations(sf);
		boolean foundDigest = false;
		for (int off = sf.indexOf(digestManifestSearch); off != -1; off = sf.indexOf(digestManifestSearch, off)) {
			int start = sf.lastIndexOf('\n', off);
			String result = null;
			if (start != -1) {
				// Signature-Version has to start the file, so there
				// should always be a newline at the start of
				// Digest-Manifest
				String digestName = sf.substring(start + 1, off);
				if (digestName.equals("MD5")) { //$NON-NLS-1$
					if (manifestMD5Result == null) {
						manifestMD5Result = calculateDigest(md5, manifestBytes);
					}
					result = manifestMD5Result;
				} else if (digestName.equals("SHA1")) { //$NON-NLS-1$
					if (manifestSHAResult == null) {
						manifestSHAResult = calculateDigest(sha1, manifestBytes);
					}
					result = manifestSHAResult;
				}
				off += digestManifestSearchLen;
				if (result == null || !sf.startsWith(result, off)) {
					// XXX should log something here
					// Skip the signature since we can't verify
					foundDigest = false;
					break;
				}
				foundDigest = true;
			}
		}
		return foundDigest;
	}

	/**
	 * Returns the Base64 encoded digest of the passed set of bytes.
	 */
	private String calculateDigest(MessageDigest digest, byte[] bytes) {
		String result;
		try {
			digest = (MessageDigest) digest.clone();
			result = new String(Base64.encode(digest.digest(bytes)));
		} catch (CloneNotSupportedException e1) {
			// Won't happen since clone is supported by
			// MessageDigest
			throw new RuntimeException(digest.getAlgorithm() + " doesn't support clone()"); //$NON-NLS-1$
		}
		return result;
	}

	static public void main(String args[]) throws IOException {

		ZipBundleFile jf = new ZipBundleFile(new File(args[0]), null);
		SignedBundleFile sr = new SignedBundleFile();

		sr.setBundleFile(jf);

		// read the first level directory entries
		Enumeration en = sr.getEntryPaths("/"); //$NON-NLS-1$
		while (en.hasMoreElements()) {
			String filePath = (String) en.nextElement();
			System.out.println("main(): " + filePath); //$NON-NLS-1$

			// if this file is not a directory file
			// then we'll get its input stream for testing
			if (filePath.indexOf('/') == -1) {
				BundleEntry be = sr.getEntry(filePath);
				InputStream is = be.getInputStream();
				is.skip(be.getSize());
				is.read();
				is.close();
			}
		}

		if (!sr.isSigned()) {
			System.out.println("No signers present"); //$NON-NLS-1$
		} else {
			CertificateChain[] chains = sr.getChains();
			for (int i = 0; i < chains.length; i++) {
				System.out.println(chains[i].getChain());
			}
		}

		System.out.println("Done"); //$NON-NLS-1$		
	}

	public File getFile(String path, boolean nativeCode) {
		return bundleFile.getFile(path, nativeCode);
	}

	public BundleEntry getEntry(String path) {
		BundleEntry be = bundleFile.getEntry(path);
		if (be == null) {
			if (digests4entries != null && digests4entries.get(path) == null)
				return null;
			throw new RuntimeException("A file has been removed from the bundle: " + getBaseFile().toString() + " : " + path); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (be.getName().startsWith("META-INF/")) //$NON-NLS-1$
			return be;
		if (!isSigned())
			// If there is no signatures, we just return the regular bundle entry
			return be;
		return new SignedBundleEntry(be);
	}

	public Enumeration getEntryPaths(String path) {
		return bundleFile.getEntryPaths(path);
	}

	public void close() throws IOException {
		bundleFile.close();
	}

	public void open() throws IOException {
		bundleFile.open();
	}

	public boolean containsDir(String dir) {
		return bundleFile.containsDir(dir);
	}

	boolean matchDNChain(String pattern) {
		CertificateChain[] matchChains = getChains();
		for (int i = 0; i < matchChains.length; i++)
			if (matchChains[i].isTrusted() && DNChainMatching.match(matchChains[i].getChain(), pattern))
				return true;
		return false;
	}

	public File getBaseFile() {
		return bundleFile.getBaseFile();
	}

	class SignedBundleEntry extends BundleEntry {
		BundleEntry nestedEntry;

		SignedBundleEntry(BundleEntry nestedEntry) {
			this.nestedEntry = nestedEntry;
		}

		public InputStream getInputStream() throws IOException {
			String name = getName();
			MessageDigest digests[] = digests4entries == null ? null : (MessageDigest[]) digests4entries.get(name);
			if (digests == null)
				return null; // return null if the digest does not exist
			byte results[][] = (byte[][]) results4entries.get(name);
			return new DigestedInputStream(nestedEntry.getInputStream(), digests, results, nestedEntry.getSize());
		}

		public long getSize() {
			return nestedEntry.getSize();
		}

		public String getName() {
			return nestedEntry.getName();
		}

		public long getTime() {
			return nestedEntry.getTime();
		}

		public URL getLocalURL() {
			return nestedEntry.getLocalURL();
		}

		public URL getFileURL() {
			return nestedEntry.getFileURL();
		}

	}

	public String[] verifyContent() {
		if (!isSigned())
			return EMPTY_STRING;
		ArrayList corrupted = new ArrayList(0); // be optimistic
		for (Enumeration entries = digests4entries.keys(); entries.hasMoreElements();) {
			String name = (String) entries.nextElement();
			BundleEntry entry = getEntry(name);
			if (entry == null)
				corrupted.add(name); // we expected the entry to be here
			else
				try {
					entry.getBytes();
				} catch (IOException e) {
					// must be invalid
					corrupted.add(name);
				}
		}
		return corrupted.size() == 0 ? EMPTY_STRING : (String[]) corrupted.toArray(new String[corrupted.size()]);
	}

	public CertificateChain[] getChains() {
		if (!isSigned())
			return new CertificateChain[0];
		return chains;
	}

	public boolean isSigned() {
		return chains != null;
	}

}