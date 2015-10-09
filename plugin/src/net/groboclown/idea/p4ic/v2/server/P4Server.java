/*
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

package net.groboclown.idea.p4ic.v2.server;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.LocalChangeList;
import com.intellij.openapi.vfs.VirtualFile;
import com.perforce.p4java.core.file.IExtendedFileSpec;
import com.perforce.p4java.core.file.IFileRevisionData;
import com.perforce.p4java.core.file.IFileSpec;
import net.groboclown.idea.p4ic.P4Bundle;
import net.groboclown.idea.p4ic.changes.P4ChangeListId;
import net.groboclown.idea.p4ic.config.ServerConfig;
import net.groboclown.idea.p4ic.server.FileSpecUtil;
import net.groboclown.idea.p4ic.server.P4StatusMessage;
import net.groboclown.idea.p4ic.server.exceptions.P4DisconnectedException;
import net.groboclown.idea.p4ic.server.exceptions.P4Exception;
import net.groboclown.idea.p4ic.server.exceptions.P4FileException;
import net.groboclown.idea.p4ic.v2.changes.P4ChangeListJob;
import net.groboclown.idea.p4ic.v2.changes.P4ChangeListMapping;
import net.groboclown.idea.p4ic.v2.history.P4AnnotatedLine;
import net.groboclown.idea.p4ic.v2.history.P4FileRevision;
import net.groboclown.idea.p4ic.v2.server.cache.ClientServerId;
import net.groboclown.idea.p4ic.v2.server.cache.P4ChangeListValue;
import net.groboclown.idea.p4ic.v2.server.cache.state.PendingUpdateState;
import net.groboclown.idea.p4ic.v2.server.cache.sync.ClientCacheManager;
import net.groboclown.idea.p4ic.v2.server.connection.*;
import net.groboclown.idea.p4ic.v2.server.connection.ServerConnection.CacheQuery;
import net.groboclown.idea.p4ic.v2.server.connection.ServerConnection.CreateUpdate;
import net.groboclown.idea.p4ic.v2.server.util.FilePathUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.Serializable;
import java.util.*;
import java.util.Map.Entry;

/**
 * Top-level manager for handling communication with the Perforce server
 * for a single client/server connection.
 * <p/>
 * The owner of this object needs to be aware of config changes; those
 * signal that the server instances are no longer valid.
 * It should listen to {@link net.groboclown.idea.p4ic.v2.events.BaseConfigUpdatedListener#TOPIC} events, which
 * are generated by {@link net.groboclown.idea.p4ic.config.P4ConfigProject}.
 * <p/>
 * The owner should also only save the state for valid server objects.
 * <p/>
 * It connects to a {@link ServerConnection}.
 */
public class P4Server {
    private static final Logger LOG = Logger.getInstance(P4Server.class);

    private final Project project;
    private final ServerConnection connection;
    private final AlertManager alertManager;
    private final ProjectConfigSource source;

    private boolean valid = true;

    /**
     * Contains data for integrating one file to another file.
     * It specifically has reference data for handling the
     * situation where the source file is on another client
     * in the same server.  It is up to the caller to discover
     * whether the source file is on the same server or not.
     */
    public static final class IntegrateFile {
        private final ClientServerId sourceClient;
        private final FilePath sourceFile;
        private final FilePath targetFile;

        public IntegrateFile(@NotNull FilePath sourceFile, @NotNull FilePath targetFile) {
            this(null, sourceFile, targetFile);
        }

        public IntegrateFile(@Nullable ClientServerId sourceClient, @NotNull FilePath sourceFile,
                @NotNull FilePath targetFile) {
            this.sourceClient = sourceClient;
            this.sourceFile = sourceFile;
            this.targetFile = targetFile;
        }

        @Nullable
        public ClientServerId getSourceClient() {
            return sourceClient;
        }

        @NotNull
        public FilePath getSourceFile() {
            return sourceFile;
        }

        @NotNull
        public FilePath getTargetFile() {
            return targetFile;
        }

        @Override
        public String toString() {
            return "Integrate(" + (sourceClient == null
                    ? ""
                    : (sourceClient + "::")
                ) + sourceFile + " -> " + targetFile + ")";
        }
    }




    P4Server(@NotNull final Project project, @NotNull final ProjectConfigSource source) {
        this.project = project;
        this.alertManager = AlertManager.getInstance();
        this.source = source;
        //this.clientState = AllClientsState.getInstance().getStateForClient(clientServerId);
        this.connection = ServerConnectionManager.getInstance().getConnectionFor(
                source.getClientServerId(), source.getServerConfig());
        connection.postSetup(project);

        // Do not reload the caches early.
        // TODO figure out if this is the right behavior.
    }


    @NotNull
    public Project getProject() {
        return project;
    }


    public boolean isValid() {
        return valid;
    }

    public boolean isWorkingOnline() {
        return valid && connection.isWorkingOnline();
    }
    public boolean isWorkingOffline() {
        return ! valid || connection.isWorkingOffline();
    }


    public void workOffline() {
        if (valid) {
            connection.workOffline();
        }
    }

    public void workOnline() {
        if (valid) {
            connection.workOnline();
        }
    }


    /**
     * This does not perform link expansion (get absolute path).  We
     * assume that if you have a file under a path in a link, you want
     * it to be at that location, and not at its real location.
     *
     * @param file file to match against this client's root directories.
     * @return the directory depth at which this file is in the client.  This is the shallowest depth for all
     *      the client roots.  It returns -1 if there is no match.
     */
    int getFilePathMatchDepth(@NotNull FilePath file) throws InterruptedException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Finding depth for " + file + " in " + getClientName());
        }
        if (! valid) {
            return -1;
        }

        final List<File> inputParts = getPathParts(file);

        boolean hadMatch = false;
        int shallowest = Integer.MAX_VALUE;
        for (List<File> rootParts: getRoots()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("- checking " + rootParts.get(rootParts.size() - 1));
            }

            if (inputParts.size() < rootParts.size()) {
                // input is at a higher ancestor level than the root parts,
                // so there's no way it could be in this root.

                LOG.debug("-- input is parent of root");

                continue;
            }

            // See if input is under the root.
            // We should be able to just call input.isUnder(configRoot), but
            // that seems to be buggy - it reported that "/a/b/c" was under "/a/b/d".

            final File sameRootDepth = inputParts.get(rootParts.size() - 1);
            if (FileUtil.filesEqual(sameRootDepth, rootParts.get(rootParts.size() - 1))) {
                LOG.debug("-- matched");

                // it's a match.  The input file ancestor path that is
                // at the same directory depth as the config root is the same
                // path.
                if (shallowest > rootParts.size()) {
                    shallowest = rootParts.size();
                    LOG.debug("--- shallowest");
                    hadMatch = true;
                }

                // Redundant - no code after this if block
                //continue;
            } else if (LOG.isDebugEnabled()) {
                LOG.debug("-- not matched " + rootParts.get(rootParts.size() - 1) + " vs " + file + " (" + sameRootDepth + ")");
            }

            // Not under the same path, so it's not a match.  Advance to next root.
        }
        return hadMatch ? shallowest : -1;
    }

    /**
     * The root directories that this perforce client covers in this project.
     * It starts with the client workspace directories, then those are stripped
     * down to just the files in the project, then those are limited by the
     * location of the perforce config directory.
     *
     * @return the actual client root directories used by the workspace,
     *      split by parent directories.
     * @throws InterruptedException
     */
    @NotNull
    public List<List<File>> getRoots() throws InterruptedException {
        // use the ProjectConfigSource as the lowest level these can be under.
        final Set<List<File>> ret = new HashSet<List<File>>();
        final List<VirtualFile> projectRoots = source.getProjectSourceDirs();
        List<List<File>> projectRootsParts = new ArrayList<List<File>>(projectRoots.size());
        for (VirtualFile projectRoot: projectRoots) {
            projectRootsParts.add(getPathParts(FilePathUtil.getFilePath(projectRoot)));
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("- project roots: " + projectRoots);
            LOG.debug("- client roots: " + getProjectClientRoots());
        }

        // VfsUtilCore.isAncestor seems to bug out at times.
        // Use the File, File version instead.

        for (VirtualFile root : getProjectClientRoots()) {
            final List<File> rootParts = getPathParts(FilePathUtil.getFilePath(root));
            for (List<File> projectRootParts : projectRootsParts) {
                if (projectRootParts.size() >= rootParts.size()) {
                    // projectRoot could be a child of (or is) root
                    if (FileUtil.filesEqual(
                            projectRootParts.get(rootParts.size() - 1),
                            rootParts.get(rootParts.size() - 1))) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("-- projectRoot " + projectRootParts.get(projectRootParts.size() - 1) +
                                    " child of " + root + ", so using the project root");
                        }
                        ret.add(projectRootParts);
                    }
                } else if (rootParts.size() >= projectRootParts.size()) {
                    // root could be a child of (or is) projectRoot
                    if (FileUtil.filesEqual(
                            projectRootParts.get(projectRootParts.size() - 1),
                            rootParts.get(projectRootParts.size() - 1))) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("-- root " + root +
                                    " child of " + projectRootParts
                                    .get(projectRootParts.size() - 1) + ", so using the root");
                        }
                        ret.add(rootParts);
                    }
                }
            }

            // If it is not in any project root, then ignore it.
        }

        // The list could be further simplified, but this should
        // be sufficient.  (Simplification: remove directories that
        // are children of existing directories in the list)

        return new ArrayList<List<File>>(ret);
    }


    /**
     * Returns the client workspace roots limited to the project.  These may be
     * wider than what should be used.
     *
     * @return project-based roots
     * @throws InterruptedException
     */
    private List<VirtualFile> getProjectClientRoots() throws InterruptedException {
        return connection.cacheQuery(new CacheQuery<List<VirtualFile>>() {
            @Override
            public List<VirtualFile> query(@NotNull final ClientCacheManager mgr) throws InterruptedException {
                if (isWorkingOnline()) {
                    LOG.debug("working online; loading the client roots cache");
                    connection.query(project, mgr.createWorkspaceRefreshQuery());
                } else {
                    LOG.debug("working offline; using cached files.");
                }
                return mgr.getClientRoots(project, alertManager);
            }
        });
    }


    /**
     *
     * @param files files to grab server status
     * @return null if working disconnected, otherwise the server status of the files.
     */
    @Nullable
    public Map<FilePath, IExtendedFileSpec> getFileStatus(@NotNull final Collection<FilePath> files)
            throws InterruptedException {
        if (files.isEmpty()) {
            return Collections.emptyMap();
        }
        if (isWorkingOffline()) {
            return null;
        }
        List<FilePath> filePathList = new ArrayList<FilePath>(files);
        final Iterator<FilePath> iter = filePathList.iterator();
        while (iter.hasNext()) {
            // Strip out directories, to ensure we have a valid mapping
            final FilePath next = iter.next();
            if (next.isDirectory()) {
                iter.remove();
            }
        }

        final List<IFileSpec> fileSpecs;
        try {
            fileSpecs = FileSpecUtil.getFromFilePaths(filePathList);
        } catch (P4Exception e) {
            alertManager.addWarning(P4Bundle.message("error.file-status.fetch", files), e);
            return null;
        }
        final List<IExtendedFileSpec> extended = connection.query(project, new ServerQuery<List<IExtendedFileSpec>>() {
            @Nullable
            @Override
            public List<IExtendedFileSpec> query(@NotNull final P4Exec2 exec,
                    @NotNull final ClientCacheManager cacheManager,
                    @NotNull final ServerConnection connection, @NotNull final AlertManager alerts)
                    throws InterruptedException {
                try {
                    return exec.getFileStatus(fileSpecs);
                } catch (VcsException e) {
                    alertManager.addWarning(P4Bundle.message("error.file-status.fetch", files), e);
                    return null;
                }
            }
        });
        if (extended == null) {
            return null;
        }

        // FIXME perform better matching

        // should be a 1-to-1 mapping
        if (filePathList.size() != extended.size()) {
            StringBuilder sb = new StringBuilder("did not match ");
            sb.append(filePathList).append(" against [");
            for (IExtendedFileSpec extendedFileSpec: extended) {
                sb
                    .append(" {")
                    .append(extendedFileSpec.getOpStatus()).append(":")
                    .append(extendedFileSpec.getStatusMessage()).append("::")
                    .append(extendedFileSpec.getDepotPath())
                    .append("} ");
            }
            sb.append("]");
            throw new IllegalStateException(sb.toString());
        }

        Map<FilePath, IExtendedFileSpec> ret = new HashMap<FilePath, IExtendedFileSpec>();
        for (int i = 0; i < filePathList.size(); i++) {
            LOG.info("Mapped " + filePathList.get(i) + " to " + extended.get(i));
            ret.put(filePathList.get(i), extended.get(i));
        }
        return ret;
    }


    /**
     * Return all files open for edit (or move, delete, etc) on this client.
     *
     * @return opened files state
     */
    @NotNull
    public Collection<P4FileAction> getOpenFiles() throws InterruptedException {
        return connection.cacheQuery(new CacheQuery<Collection<P4FileAction>>() {
            @Override
            public Collection<P4FileAction> query(@NotNull final ClientCacheManager mgr) throws InterruptedException {
                if (isWorkingOnline()) {
                    connection.query(project, mgr.createFileActionsRefreshQuery());
                }
                return mgr.getCachedOpenFiles();
            }
        });
    }


    /**
     * Needs to be run immediately.
     *
     * @param files files to add or  edit
     * @param changelistId changelist id
     */
    public void addOrEditFiles(@NotNull final List<VirtualFile> files, final int changelistId) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Add or edit to " + changelistId + " files " + files);
        }
        if (files.isEmpty()) {
            return;
        }
        connection.queueUpdates(project, new CreateUpdate() {
            @Override
            public Collection<PendingUpdateState> create(@NotNull final ClientCacheManager mgr) {

                // FIXME rollback implementation
                // For rollback, we can use the internal IDE history to capture what to rollback to.
                // Need to inspect their API around that.


                List<PendingUpdateState> updates = new ArrayList<PendingUpdateState>();
                for (VirtualFile file : files) {
                    final PendingUpdateState update = mgr.addOrEditFile(FilePathUtil.getFilePath(file), changelistId);
                    if (update != null) {
                        LOG.info("add pending update " + update);
                        updates.add(update);
                    } else {
                        LOG.info("add/edit caused no update: " + file);
                    }
                }
                return updates;
            }
        });
    }

    public void onlyEditFile(@NotNull final VirtualFile file, final int changelistId) {
        // Bug #6
        //   Add/Edit without adding to Perforce incorrectly
        //   then adds the file to Perforce
        // This method is called when a save happens, which can be
        // at any time.  If the save is called on a file which is
        // marked as locally updated but not checked out, this will
        // still be called.  This method should never *add* a file
        // into Perforce - only open for edit.

        connection.queueUpdates(project, new CreateUpdate() {
            @NotNull
            @Override
            public Collection<PendingUpdateState> create(@NotNull ClientCacheManager mgr) {
                final PendingUpdateState update = mgr.editFile(file, changelistId);
                if (update != null) {
                    LOG.info("add pending update " + update);
                    return Collections.singletonList(update);
                } else {
                    LOG.info("add/edit caused no update: " + file);
                    return Collections.emptyList();
                }
            }
        });
    }

    public void moveFiles(@NotNull final List<IntegrateFile> filePathMatch, final int changelistId) {
        if (filePathMatch.isEmpty()) {
            return;
        }
        // FIXME implement
        throw new IllegalStateException("not implemented");
    }

    public void integrateFiles(@NotNull final List<IntegrateFile> integrationFiles, final int changelistId) {
        if (integrationFiles.isEmpty()) {
            return;
        }
        // FIXME implement
        throw new IllegalStateException("not implemented");
    }

    public void revertFiles(@NotNull final List<FilePath> files) {
        if (files.isEmpty()) {
            return;
        }
        // FIXME implement
        throw new IllegalStateException("not implemented");
    }

    /**
     *
     * @param filePaths file to revert.
     * @return all files that were reverted
     */
    @NotNull
    public MessageResult<Collection<FilePath>> revertUnchangedFiles(@NotNull final Collection<FilePath> filePaths) {
        if (filePaths.isEmpty()) {
            return new MessageResult<Collection<FilePath>>(
                    Collections.<FilePath>emptyList(), Collections.<P4StatusMessage>emptyList());
        }
        // FIXME implement
        throw new IllegalStateException("not implemented");
    }


    public MessageResult<Collection<FileSyncResult>> synchronizeFiles(@NotNull final Collection<FilePath> files, final int revisionNumber,
            @Nullable final String syncSpec, final boolean force) {
        if (files.isEmpty()) {
            return new MessageResult<Collection<FileSyncResult>>(
                    Collections.<FileSyncResult>emptyList(), Collections.<P4StatusMessage>emptyList());
        }
        // FIXME implement
        throw new IllegalStateException("not implemented");
    }

    public Collection<P4ChangeListValue> getOpenChangeLists() throws InterruptedException {
        return connection.cacheQuery(new CacheQuery<Collection<P4ChangeListValue>>() {
            @Override
            public Collection<P4ChangeListValue> query(@NotNull final ClientCacheManager mgr) throws InterruptedException {
                if (isWorkingOnline()) {
                    connection.query(project, mgr.createChangeListRefreshQuery());
                }
                return mgr.getCachedOpenedChanges();
            }
        });
    }

    public void deleteChangelist(final int changeListId) {
        if (changeListId == P4ChangeListId.P4_DEFAULT || changeListId == P4ChangeListId.P4_UNKNOWN) {
            return;
        }
        connection.queueUpdates(project, new CreateUpdate() {
            @NotNull
            @Override
            public Collection<PendingUpdateState> create(@NotNull final ClientCacheManager mgr) {
                final PendingUpdateState update = mgr.deleteChangelist(changeListId);
                if (update == null) {
                    return Collections.emptyList();
                }
                return Collections.singletonList(update);
            }
        });
    }

    public void renameChangelist(final int changeListId, final String description) {
        if (changeListId == P4ChangeListId.P4_DEFAULT || changeListId == P4ChangeListId.P4_UNKNOWN) {
            return;
        }
        connection.queueUpdates(project, new CreateUpdate() {
            @NotNull
            @Override
            public Collection<PendingUpdateState> create(@NotNull final ClientCacheManager mgr) {
                PendingUpdateState update = mgr.renameChangelist(changeListId, description);
                if (update == null) {
                    return Collections.emptyList();
                }
                return Collections.singletonList(update);
            }
        });
    }

    /**
     * @param filePaths files to move
     * @param source used to discover which changelist will contain the files.  The changelist
     *               ID isn't necessary to pass in, as this will do discovery in the case of the
     * @param changeListMapping the mapping between p4 changes and the IDEA changes.
     */
    public void moveFilesToChange(@NotNull List<FilePath> filePaths, @NotNull final LocalChangeList source,
            final P4ChangeListMapping changeListMapping) {
        if (! filePaths.isEmpty()) {
            final List<FilePath> filePathCopy = new ArrayList<FilePath>(filePaths);
            connection.queueUpdates(project, new CreateUpdate() {
                @NotNull
                @Override
                public Collection<PendingUpdateState> create(@NotNull final ClientCacheManager mgr) {
                    // Because this operation can potentially create a new P4 changelist, and
                    // because the files MUST be assigned to a real changelist number when the
                    // edit occurs, this operation must be run as a single action.  Otherwise,
                    // there'd be the state where a newly associated Perforce changelist number
                    // has files that should move into it, but they still point to the old
                    // (local) changelist number; that would require keeping invalid local
                    // changelists around until the associated files used it, and that would be
                    // a mess to properly detect and clean up.

                    // To add to the mess, this method can be called by the P4ChangelistListener
                    // after ChangeListSync causes things to move into the correct changelist.
                    // This can lead to all kinds of terrible performance issues.

                    P4ChangeListId clid = changeListMapping.getPerforceChangelistFor(P4Server.this, source);
                    if (clid == null) {
                        clid = mgr.reserveLocalChangelistId();
                        // make sure we create this association in the changelist mapping.
                        P4ChangeListMapping.getInstance(project).bindChangelists(source, clid);
                    } else {
                        // quick check to see if we already have that file in that changelist.
                        final Collection<P4FileAction> opened = mgr.getCachedOpenFiles();
                        for (P4FileAction action : opened) {
                            if (action.getChangeList() == clid.getChangeListId() &&
                                    filePathCopy.contains(action.getFile())) {
                                LOG.info("Ignoring move-to-changelist " + clid + " request for " + action.getFile());
                                filePathCopy.remove(action.getFile());
                            }
                        }
                    }

                    PendingUpdateState update = mgr.moveFilesToChangelist(filePathCopy, source, clid.getChangeListId());
                    if (update == null) {
                        return Collections.emptyList();
                    }
                    return Collections.singletonList(update);
                }
            });
        }
    }

    /**
     * Set by the owning manager.
     *
     * @param isValid valid state
     */
    void setValid(boolean isValid) {
        valid = isValid;
    }


    public void dispose() {
        valid = false;
    }

    @NotNull
    public ClientServerId getClientServerId() {
        return source.getClientServerId();
    }

    @NotNull
    public ServerConfig getServerConfig() {
        return source.getServerConfig();
    }

    @Nullable
    public String getClientName() {
        return source.getClientName();
    }

    /**
     * Check if the given file is ignored by version control.
     *
     * @param fp file or directory to check
     * @return true if ignored, which includes directories.
     */
    public boolean isIgnored(@Nullable final FilePath fp) throws InterruptedException {
        if (fp == null || fp.isDirectory()) {
            return true;
        }
        return connection.cacheQuery(new CacheQuery<Boolean>() {
            @Override
            public Boolean query(@NotNull final ClientCacheManager mgr) throws InterruptedException {
                return mgr.isIgnored(fp);
            }
        });
    }

    /**
     * Fetch the file spec's contents.  If the file does not exist or is deleted,
     * it returns null.  If the filespec is invalid or the server is not connected,
     * an exception is thrown.
     *
     * @param spec file spec to read
     * @return the file contents
     * @throws P4FileException
     * @throws P4DisconnectedException
     */
    @Nullable
    public String loadFileAsString(@NotNull IFileSpec spec)
            throws P4FileException, P4DisconnectedException {
        // FIXME implement
        throw new IllegalStateException("not implemented");
    }

    @Nullable
    public String loadFileAsString(@NotNull FilePath file, final int rev)
            throws P4FileException, P4DisconnectedException {
        // FIXME implement
        throw new IllegalStateException("not implemented");
    }

    @Nullable
    public byte[] loadFileAsBytes(@NotNull IFileSpec spec) {
        // FIXME implement
        throw new IllegalStateException("not implemented");
    }

    @Nullable
    public byte[] loadFileAsBytes(@NotNull FilePath file, final int rev) {
        // FIXME implement
        throw new IllegalStateException("not implemented");
    }

    public void deleteFiles(@NotNull final List<FilePath> files, final int changelistId) {
        if (files.isEmpty()) {
            return;
        }
        connection.queueUpdates(project, new CreateUpdate() {
            @NotNull
            @Override
            public Collection<PendingUpdateState> create(@NotNull final ClientCacheManager mgr) {
                List<PendingUpdateState> ret = new ArrayList<PendingUpdateState>(files.size());
                for (FilePath file : files) {
                    PendingUpdateState update = mgr.deleteFile(file, changelistId);
                    if (update != null) {
                        // FIXME debug
                        LOG.info("Created delete update for " + file);
                        ret.add(update);
                    } else {
                        // FIXME debug
                        LOG.info("Ignored delete update for " + file);
                    }
                }
                return ret;
            }
        });
    }

    public void submitChangelist(final List<FilePath> files, final List<P4ChangeListJob> jobs, final String submitStatus,
            final int changeListId) {
        // FIXME implement
        throw new IllegalStateException("not implemented");
    }

    @NotNull
    public List<P4AnnotatedLine> getAnnotationsFor(@NotNull final IFileSpec spec, final int revNumber)
            throws VcsException, InterruptedException {
        if (isWorkingOffline()) {
            throw new P4DisconnectedException();
        }
        final Ref<VcsException> ex = new Ref<VcsException>();
        final List<P4AnnotatedLine> ret = connection.query(project, new ServerQuery<List<P4AnnotatedLine>>() {
            @Nullable
            @Override
            public List<P4AnnotatedLine> query(@NotNull final P4Exec2 exec,
                    @NotNull final ClientCacheManager cacheManager,
                    @NotNull final ServerConnection connection, @NotNull final AlertManager alerts)
                    throws InterruptedException {
                try {
                    IFileSpec usedSpec = spec;
                    if (revNumber > 0) {
                        usedSpec = FileSpecUtil.getAlreadyEscapedSpec(spec.getDepotPathString() + '#' + revNumber);
                    }
                    return P4AnnotatedLine.loadAnnotatedLines(exec,
                            exec.getAnnotationsFor(Collections.singletonList(usedSpec)));
                } catch (VcsException e) {
                    ex.set(e);
                    return null;
                }
            }
        });
        if (! ex.isNull()) {
            throw ex.get();
        }
        if (ret == null) {
            throw new P4FileException(spec.getDepotPathString());
        }
        return ret;
    }

    @Nullable
    public List<P4FileRevision> getRevisionHistory(@NotNull final IExtendedFileSpec spec,
            final int limit) throws InterruptedException {
        return connection.query(project, new ServerQuery<List<P4FileRevision>>() {
            @Nullable
            @Override
            public List<P4FileRevision> query(@NotNull final P4Exec2 exec,
                    @NotNull final ClientCacheManager cacheManager,
                    @NotNull final ServerConnection connection, @NotNull final AlertManager alerts)
                    throws InterruptedException {

                // FIXME there's a bug here where getting the revision for a file with a special character won't return
                // any history.  e.g.
                // //depot/projecta/hotfix/a/test%23one.txt returns map {null=null}

                Map<IFileSpec, List<IFileRevisionData>> history;
                try {
                    history = exec.getRevisionHistory(Collections.<IFileSpec>singletonList(spec), limit);
                } catch (VcsException e) {
                    alerts.addNotice(P4Bundle.message("error.revision-history", spec.getDepotPathString()), e);
                    return null;
                }
                LOG.info("history for " + spec.getDepotPathString() + ": " + history);

                List<P4FileRevision> ret = new ArrayList<P4FileRevision>();
                for (Entry<IFileSpec, List<IFileRevisionData>> entry : history.entrySet()) {
                    if (entry.getValue() == null) {
                        LOG.info("history for " + spec.getDepotPathString() + ": null values for " + entry.getKey());
                    } else {
                        for (IFileRevisionData rev : entry.getValue()) {
                            if (rev != null) {
                                final P4FileRevision p4rev = createRevision(entry.getKey(), rev, alerts);
                                if (p4rev != null) {
                                    ret.add(p4rev);
                                }
                            }
                        }
                    }
                }

                // Note that these are not sorted.  Sort by date.
                Collections.sort(ret, REV_COMPARE);

                return ret;
            }
        });
    }

    @NotNull
    public Collection<String> getJobStatusValues() throws InterruptedException {
        final Collection<String> ret = connection.cacheQuery(new CacheQuery<Collection<String>>() {
            @Override
            public Collection<String> query(@NotNull final ClientCacheManager mgr) throws InterruptedException {
                if (isWorkingOnline()) {
                    connection.query(project, mgr.createJobStatusListRefreshQuery());
                }
                return mgr.getCachedJobStatusList();
            }
        });
        if (ret == null) {
            return Collections.emptyList();
        }
        return ret;
    }

    @NotNull
    public Map<String, P4ChangeListJob> getJobsForIds(@NotNull final Collection<String> jobId) throws InterruptedException {
        if (jobId.isEmpty()) {
            return Collections.emptyMap();
        }
        return connection.cacheQuery(new CacheQuery<Map<String, P4ChangeListJob>>() {
            @Override
            public Map<String, P4ChangeListJob> query(@NotNull final ClientCacheManager mgr) throws InterruptedException {
                if (isWorkingOnline()) {
                    connection.query(project, mgr.createJobRefreshQuery(jobId));
                }
                return mgr.getCachedJobIds(jobId);
            }
        });
    }


    @NotNull
    public Collection<P4ChangeListJob> getJobsInChangelists(@NotNull final Collection<P4ChangeListId> changes)
            throws InterruptedException {
        final Collection<P4ChangeListJob> ret = connection.cacheQuery(new CacheQuery<Collection<P4ChangeListJob>>() {
            @Override
            public Collection<P4ChangeListJob> query(@NotNull final ClientCacheManager mgr) throws InterruptedException {
                if (isWorkingOnline()) {
                    connection.query(project, mgr.createChangeListRefreshQuery());
                }
                return mgr.getCachedJobsInChangelists(changes);
            }
        });
        if (ret == null) {
            return Collections.emptyList();
        }
        return ret;
    }

    @Override
    public String toString() {
        return getClientServerId().toString();
    }

    @NotNull
    private List<File> getPathParts(@NotNull final FilePath child) {
        List<File> ret = new ArrayList<File>();
        FilePath next = child;
        while (next != null) {
            ret.add(next.getIOFile());
            next = next.getParentPath();
        }
        Collections.reverse(ret);
        return ret;
    }


    @Nullable
    private P4FileRevision createRevision(@NotNull IFileSpec spec,
            @NotNull final IFileRevisionData rev, final AlertManager alerts) {
        if (spec.getDepotPathString() == null && rev.getDepotFileName() == null) {
            alerts.addNotice(P4Bundle.message("error.revision-null", spec), null);
            return null;
        }
        LOG.info("Finding location of " + spec);
        // Note: check above performs the NPE checks.
        return new P4FileRevision(project, getClientServerId(),
                spec.getDepotPathString(), rev.getDepotFileName(), rev);
    }

    private static final RevCompare REV_COMPARE = new RevCompare();

    // Comparator must implement Serializable
    private static class RevCompare implements Comparator<P4FileRevision>, Serializable {
        @Override
        public int compare(final P4FileRevision o1, final P4FileRevision o2) {
            // compare in reverse order
            return o2.getRevisionDate().compareTo(o1.getRevisionDate());
        }
    }
}
