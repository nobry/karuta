package eportfolium.com.karuta.model.bean;
// Generated 13 juin 2019 19:14:13 by Hibernate Tools 5.2.10.Final

import java.io.Serializable;
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Embeddable;
import javax.persistence.ManyToOne;

/**
 * GroupRightsId generated by hbm2java
 */
@Embeddable
public class GroupRightsId implements Serializable {

	private static final long serialVersionUID = 7042573400581202433L;

	private GroupRightInfo groupRightInfo;
	
	// Association with Node entity ?
	private UUID id;

	public GroupRightsId() {
	}

	public GroupRightsId(GroupRightInfo groupRightInfo, UUID id) {
		this.groupRightInfo = groupRightInfo;
		this.id = id;
	}

	@ManyToOne
	public GroupRightInfo getGroupRightInfo() {
		return this.groupRightInfo;
	}

	public void setGroupRightInfo(GroupRightInfo groupRightInfo) {
		this.groupRightInfo = groupRightInfo;
	}

	@Column(name = "id", nullable = false)
	public UUID getId() {
		return this.id;
	}

	public void setId(UUID id) {
		this.id = id;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((groupRightInfo == null) ? 0 : groupRightInfo.hashCode());
		result = prime * result + ((id == null) ? 0 : id.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		GroupRightsId other = (GroupRightsId) obj;
		if (groupRightInfo == null) {
			if (other.groupRightInfo != null)
				return false;
		} else if (!groupRightInfo.equals(other.groupRightInfo))
			return false;
		if (id == null) {
			if (other.id != null)
				return false;
		} else if (!id.equals(other.id))
			return false;
		return true;
	}

}