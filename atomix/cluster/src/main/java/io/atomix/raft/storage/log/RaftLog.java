/*
 * Copyright 2017-present Open Networking Foundation
 * Copyright © 2020 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.raft.storage.log;

import io.atomix.raft.storage.log.entry.RaftLogEntry;
import io.atomix.storage.StorageLevel;
import io.atomix.storage.journal.Indexed;
import io.atomix.storage.journal.JournalReader;
import io.atomix.storage.journal.JournalWriter;
import io.atomix.storage.journal.SegmentedJournal;
import io.atomix.storage.journal.index.JournalIndex;
import io.atomix.utils.serializer.Namespace;
import java.io.Closeable;
import java.io.File;
import java.util.function.Supplier;

/** Raft log. */
public class RaftLog implements RaftLogWriter, Closeable {

  private final SegmentedJournal<RaftLogEntry> journal;
  private final boolean flushExplicitly;
  private final JournalWriter<RaftLogEntry> writer;
  private volatile long commitIndex;

  protected RaftLog(final SegmentedJournal<RaftLogEntry> journal, final boolean flushExplicitly) {
    this.journal = journal;
    this.flushExplicitly = flushExplicitly;

    writer = journal.writer();
  }

  /**
   * Returns a new Raft log builder.
   *
   * @return A new Raft log builder.
   */
  public static Builder builder() {
    return new Builder();
  }

  public RaftLogWriter writer() {
    return this;
  }

  public RaftLogReader openReader(final long index) {
    return openReader(index, JournalReader.Mode.ALL);
  }

  public RaftLogReader openReader(final long index, final JournalReader.Mode mode) {
    return new RaftLogReader(journal.openReader(index, mode));
  }

  public boolean isOpen() {
    return journal.isOpen();
  }

  /**
   * Returns a boolean indicating whether a segment can be removed from the journal prior to the
   * given index.
   *
   * @param index the index from which to remove segments
   * @return indicates whether a segment can be removed from the journal
   */
  public boolean isCompactable(final long index) {
    return journal.isCompactable(index);
  }

  /**
   * Returns the index of the last segment in the log.
   *
   * @param index the compaction index
   * @return the starting index of the last segment in the log
   */
  public long getCompactableIndex(final long index) {
    return journal.getCompactableIndex(index);
  }

  /**
   * Compacts the journal up to the given index.
   *
   * <p>The semantics of compaction are not specified by this interface.
   *
   * @param index The index up to which to compact the journal.
   */
  public void compact(final long index) {
    journal.compact(index);
  }

  /**
   * Returns the Raft log commit index.
   *
   * @return The Raft log commit index.
   */
  long getCommitIndex() {
    return commitIndex;
  }

  /**
   * Commits entries up to the given index.
   *
   * @param index The index up to which to commit entries.
   */
  void setCommitIndex(final long index) {
    commitIndex = index;
  }

  public boolean shouldFlushExplicitly() {
    return flushExplicitly;
  }

  @Override
  public long getLastIndex() {
    return writer.getLastIndex();
  }

  @Override
  public Indexed<RaftLogEntry> getLastEntry() {
    return writer.getLastEntry();
  }

  @Override
  public long getNextIndex() {
    return writer.getNextIndex();
  }

  @Override
  public <T extends RaftLogEntry> Indexed<T> append(final T entry) {
    return writer.append(entry);
  }

  @Override
  public <T extends RaftLogEntry> Indexed<T> append(final T entry, final long checksum) {
    return writer.append(entry, checksum);
  }

  @Override
  public void append(final Indexed<RaftLogEntry> entry) {
    writer.append(entry);
  }

  @Override
  public void commit(final long index) {
    writer.commit(index);
  }

  @Override
  public void reset(final long index) {
    writer.reset(index);
  }

  @Override
  public void truncate(final long index) {
    writer.truncate(index);
  }

  @Override
  public void flush() {
    writer.flush();
  }

  @Override
  public void close() {
    journal.close();
  }

  @Override
  public String toString() {
    return "RaftLog{" + "journal=" + journal + '}';
  }

  /** Raft log builder. */
  public static class Builder implements io.atomix.utils.Builder<RaftLog> {

    private final SegmentedJournal.Builder<RaftLogEntry> journalBuilder =
        SegmentedJournal.builder();
    private boolean flushExplicitly = true;

    protected Builder() {}

    /**
     * Sets the storage name.
     *
     * @param name The storage name.
     * @return The storage builder.
     */
    public Builder withName(final String name) {
      journalBuilder.withName(name);
      return this;
    }

    /**
     * Sets the log storage level, returning the builder for method chaining.
     *
     * <p>The storage level indicates how individual entries should be persisted in the journal.
     *
     * @param storageLevel The log storage level.
     * @return The storage builder.
     */
    public Builder withStorageLevel(final StorageLevel storageLevel) {
      journalBuilder.withStorageLevel(storageLevel);
      return this;
    }

    /**
     * Sets the log directory, returning the builder for method chaining.
     *
     * <p>The log will write segment files into the provided directory.
     *
     * @param directory The log directory.
     * @return The storage builder.
     * @throws NullPointerException If the {@code directory} is {@code null}
     */
    public Builder withDirectory(final String directory) {
      journalBuilder.withDirectory(directory);
      return this;
    }

    /**
     * Sets the log directory, returning the builder for method chaining.
     *
     * <p>The log will write segment files into the provided directory.
     *
     * @param directory The log directory.
     * @return The storage builder.
     * @throws NullPointerException If the {@code directory} is {@code null}
     */
    public Builder withDirectory(final File directory) {
      journalBuilder.withDirectory(directory);
      return this;
    }

    /**
     * Sets the log serialization namespace, returning the builder for method chaining.
     *
     * @param namespace The journal namespace.
     * @return The journal builder.
     */
    public Builder withNamespace(final Namespace namespace) {
      journalBuilder.withNamespace(namespace);
      return this;
    }

    /**
     * Sets the maximum segment size in bytes, returning the builder for method chaining.
     *
     * <p>The maximum segment size dictates when logs should roll over to new segments. As entries
     * are written to a segment of the log, once the size of the segment surpasses the configured
     * maximum segment size, the log will create a new segment and append new entries to that
     * segment.
     *
     * <p>By default, the maximum segment size is {@code 1024 * 1024 * 32}.
     *
     * @param maxSegmentSize The maximum segment size in bytes.
     * @return The storage builder.
     * @throws IllegalArgumentException If the {@code maxSegmentSize} is not positive
     */
    public Builder withMaxSegmentSize(final int maxSegmentSize) {
      journalBuilder.withMaxSegmentSize(maxSegmentSize);
      return this;
    }

    /**
     * Sets the maximum entry size in bytes, returning the builder for method chaining.
     *
     * @param maxEntrySize the maximum entry size in bytes
     * @return the storage builder
     * @throws IllegalArgumentException if the {@code maxEntrySize} is not positive
     */
    public Builder withMaxEntrySize(final int maxEntrySize) {
      journalBuilder.withMaxEntrySize(maxEntrySize);
      return this;
    }

    /**
     * Sets the minimum free disk space to leave when allocating a new segment
     *
     * @param freeDiskSpace free disk space in bytes
     * @return the storage builder
     * @throws IllegalArgumentException if the {@code freeDiskSpace} is not positive
     */
    public Builder withFreeDiskSpace(final long freeDiskSpace) {
      journalBuilder.withFreeDiskSpace(freeDiskSpace);
      return this;
    }

    /**
     * Sets the maximum number of allows entries per segment, returning the builder for method
     * chaining.
     *
     * <p>The maximum entry count dictates when logs should roll over to new segments. As entries
     * are written to a segment of the log, if the entry count in that segment meets the configured
     * maximum entry count, the log will create a new segment and append new entries to that
     * segment.
     *
     * <p>By default, the maximum entries per segment is {@code 1024 * 1024}.
     *
     * @param maxEntriesPerSegment The maximum number of entries allowed per segment.
     * @return The storage builder.
     * @throws IllegalArgumentException If the {@code maxEntriesPerSegment} not greater than the
     *     default max entries per segment
     * @deprecated since 3.0.2
     */
    @Deprecated
    public Builder withMaxEntriesPerSegment(final int maxEntriesPerSegment) {
      journalBuilder.withMaxEntriesPerSegment(maxEntriesPerSegment);
      return this;
    }

    /**
     * Sets whether or not to flush buffered I/O explicitly at various points, returning the builder
     * for chaining.
     *
     * <p>Enabling this ensures that entries are flushed on followers before acknowledging a write,
     * and are flushed on the leader before marking an entry as committed. This guarantees the
     * correctness of various Raft properties.
     *
     * @param flushExplicitly whether to flush explicitly or not
     * @return this builder for chaining
     */
    public Builder withFlushExplicitly(final boolean flushExplicitly) {
      this.flushExplicitly = flushExplicitly;
      return this;
    }

    public Builder withJournalIndexFactory(final Supplier<JournalIndex> journalIndexFactory) {
      journalBuilder.withJournalIndexFactory(journalIndexFactory);
      return this;
    }

    @Override
    public RaftLog build() {
      return new RaftLog(journalBuilder.build(), flushExplicitly);
    }
  }
}
