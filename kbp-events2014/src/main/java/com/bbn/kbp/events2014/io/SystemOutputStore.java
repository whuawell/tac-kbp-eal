package com.bbn.kbp.events2014.io;

import java.io.IOException;

import com.bbn.bue.common.symbols.Symbol;
import com.bbn.kbp.events2014.SystemOutput;
import com.google.common.collect.ImmutableSet;

/**
 * Represents something (e.g. a file, a database) which can store system responses for
 * the KBP event argument task. Stores should be closed when you are done with them
 * to prevent data loss.
 * @author rgabbard
 *
 */
public interface SystemOutputStore {
    /**
     * The document IDs with stored responses.
     * @return
     * @throws IOException
     */
	public ImmutableSet<Symbol> docIDs() throws IOException;

    /**
     * Gets the system output for the specified document or throws
     * an exception if it is not available.
     * @param docId
     * @return
     * @throws IOException
     */
	public SystemOutput read(Symbol docId) throws IOException;

    /**
     * Writes the provided system output to this store.
     * @param systemOutput
     * @throws IOException
     */
	public void write(SystemOutput systemOutput) throws IOException;

	public void close() throws IOException;

    /**
     * Like {@code read}, but it returns an empty system output
     * if no data is available for a document.
     * @param docid
     * @return
     * @throws IOException
     */
	public SystemOutput readOrEmpty(Symbol docid) throws IOException;
}
