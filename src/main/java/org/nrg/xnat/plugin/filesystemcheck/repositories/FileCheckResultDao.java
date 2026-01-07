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
import org.nrg.xnat.plugin.filesystemcheck.entities.FileCheckResultEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository("fsCheckFileCheckResultDao")
@Transactional
public class FileCheckResultDao {

    private final SessionFactory sessionFactory;

    @Autowired
    public FileCheckResultDao(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    protected Session getSession() {
        return sessionFactory.getCurrentSession();
    }

    public List<FileCheckResultEntity> findByCheckId(String checkId, int page, int pageSize) {
        return getSession()
                .createQuery("FROM FsCheckFileResult WHERE checkId = :checkId ORDER BY id",
                        FileCheckResultEntity.class)
                .setParameter("checkId", checkId)
                .setFirstResult(page * pageSize)
                .setMaxResults(pageSize)
                .getResultList();
    }

    public List<FileCheckResultEntity> findByCheckIdAndStatus(String checkId, String status, int page, int pageSize) {
        return getSession()
                .createQuery("FROM FsCheckFileResult WHERE checkId = :checkId AND status = :status ORDER BY id",
                        FileCheckResultEntity.class)
                .setParameter("checkId", checkId)
                .setParameter("status", status)
                .setFirstResult(page * pageSize)
                .setMaxResults(pageSize)
                .getResultList();
    }

    public long countByCheckId(String checkId) {
        return getSession()
                .createQuery("SELECT COUNT(*) FROM FsCheckFileResult WHERE checkId = :checkId", Long.class)
                .setParameter("checkId", checkId)
                .getSingleResult();
    }

    public long countByCheckIdAndStatus(String checkId, String status) {
        return getSession()
                .createQuery("SELECT COUNT(*) FROM FsCheckFileResult WHERE checkId = :checkId AND status = :status", Long.class)
                .setParameter("checkId", checkId)
                .setParameter("status", status)
                .getSingleResult();
    }

    public void deleteByCheckId(String checkId) {
        getSession()
                .createQuery("DELETE FROM FsCheckFileResult WHERE checkId = :checkId")
                .setParameter("checkId", checkId)
                .executeUpdate();
    }

    public void saveBatch(List<FileCheckResultEntity> entities) {
        Session session = getSession();
        for (int i = 0; i < entities.size(); i++) {
            session.persist(entities.get(i));
            if (i % 50 == 0) {  // Flush every 50 records
                session.flush();
                session.clear();
            }
        }
    }
}
