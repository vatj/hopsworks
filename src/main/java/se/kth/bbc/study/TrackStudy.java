/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package se.kth.bbc.study;

import java.io.Serializable;
import java.util.Collection;
import java.util.Date;
import javax.persistence.Basic;
import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.OneToMany;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;
import org.codehaus.jackson.annotate.JsonIgnore;

/**
 *
 * @author roshan
 */
@Entity
@Table(name = "study")
@XmlRootElement
@NamedQueries({
    @NamedQuery(name = "TrackStudy.findAll", query = "SELECT t FROM TrackStudy t"),
    @NamedQuery(name = "TrackStudy.findByName", query = "SELECT t FROM TrackStudy t WHERE t.name = :name"),
    @NamedQuery(name = "TrackStudy.findByUsername", query = "SELECT t FROM TrackStudy t WHERE t.username = :username"),
    @NamedQuery(name = "TrackStudy.findByTimestamp", query = "SELECT t FROM TrackStudy t WHERE t.timestamp = :timestamp"),
    @NamedQuery(name = "TrackStudy.findAllStudy", query = "SELECT count(t.name) FROM TrackStudy t WHERE t.username = :username")})
public class TrackStudy implements Serializable {
    private static final long serialVersionUID = 1L;
    @Id
    @Basic(optional = false)
    @NotNull
    @Size(min = 1, max = 128)
    @Column(name = "name")
    private String name;
    @Basic(optional = false)
    @NotNull
    @Size(min = 1, max = 255)
    @Column(name = "username")
    private String username;
    @Basic(optional = false)
    @NotNull
    @Column(name = "timestamp")
    @Temporal(TemporalType.TIMESTAMP)
    private Date timestamp;
    @OneToMany(cascade = CascadeType.ALL, mappedBy = "trackStudy")
    private Collection<StudyGroups> studyGroupsCollection;

    public TrackStudy() {
    }

    public TrackStudy(String name) {
        this.name = name;
    }

    public TrackStudy(String name, String username, Date timestamp) {
        this.name = name;
        this.username = username;
        this.timestamp = timestamp;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public Date getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Date timestamp) {
        this.timestamp = timestamp;
    }

    @XmlTransient
    @JsonIgnore
    public Collection<StudyGroups> getStudyGroupsCollection() {
        return studyGroupsCollection;
    }

    public void setStudyGroupsCollection(Collection<StudyGroups> studyGroupsCollection) {
        this.studyGroupsCollection = studyGroupsCollection;
    }

    @Override
    public int hashCode() {
        int hash = 0;
        hash += (name != null ? name.hashCode() : 0);
        return hash;
    }

    @Override
    public boolean equals(Object object) {
        // TODO: Warning - this method won't work in the case the id fields are not set
        if (!(object instanceof TrackStudy)) {
            return false;
        }
        TrackStudy other = (TrackStudy) object;
        if ((this.name == null && other.name != null) || (this.name != null && !this.name.equals(other.name))) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "se.kth.bbc.study.TrackStudy[ name=" + name + " ]";
    }
    
}
