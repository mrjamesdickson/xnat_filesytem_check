/*
 * XNAT Filesystem Check Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package org.nrg.xnat.plugin.filesystemcheck.repositories;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.nrg.xnat.plugin.filesystemcheck.entities.FilesystemCheckEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository("fsCheckFilesystemCheckDao")
@Transactional
public class FilesystemCheckDao {

    private final SessionFactory sessionFactory;

    @Autowired
    public FilesystemCheckDao(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    protected Session getSession() {
        return sessionFactory.getCurrentSession();
    }

    public void create(FilesystemCheckEntity entity) {
        getSession().persist(entity);
    }

    public void update(FilesystemCheckEntity entity) {
        getSession().merge(entity);
    }

    public FilesystemCheckEntity findByCheckId(String checkId) {
        List<FilesystemCheckEntity> results = getSession()
                .createQuery("FROM FsCheckFilesystemCheck WHERE checkId = :checkId", FilesystemCheckEntity.class)
                .setParameter("checkId", checkId)
                .getResultList();
        return results.isEmpty() ? null : results.get(0);
    }

    public List<FilesystemCheckEntity> findByUsername(String username) {
        return getSession()
                .createQuery("FROM FsCheckFilesystemCheck WHERE username = :username ORDER BY createdAt DESC", FilesystemCheckEntity.class)
                .setParameter("username", username)
                .getResultList();
    }

    public List<FilesystemCheckEntity> findActiveChecks() {
        return getSession()
                .createQuery("FROM FsCheckFilesystemCheck WHERE status IN ('queued', 'running') ORDER BY startedAt DESC",
                        FilesystemCheckEntity.class)
                .getResultList();
    }

    public List<FilesystemCheckEntity> findRecentCompleted(int limit) {
        return getSession()
                .createQuery("FROM FsCheckFilesystemCheck WHERE status IN ('completed', 'failed', 'cancelled') ORDER BY completedAt DESC",
                        FilesystemCheckEntity.class)
                .setMaxResults(limit)
                .getResultList();
    }

    public void deleteOlderThan(Instant cutoffDate) {
        getSession()
                .createQuery("DELETE FROM FsCheckFilesystemCheck WHERE completedAt < :cutoffDate AND status IN ('completed', 'failed', 'cancelled')")
                .setParameter("cutoffDate", cutoffDate)
                .executeUpdate();
    }
}
