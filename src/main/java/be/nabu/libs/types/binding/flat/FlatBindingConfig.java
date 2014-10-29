package be.nabu.libs.types.binding.flat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.annotation.XmlAnyAttribute;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElements;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlSeeAlso;
import javax.xml.bind.annotation.XmlTransient;
import javax.xml.namespace.QName;

import be.nabu.libs.types.binding.BindingConfig;

@XmlRootElement(name = "binding")
public class FlatBindingConfig extends BindingConfig {
	
	private List<Fragment> children;
	private int maxLookAhead = 1024000;
	private String record;
	
	@XmlElements({
		@XmlElement(name = "record", type = Record.class),
		@XmlElement(name = "field", type = Field.class)
	})
	public List<Fragment> getChildren() {
		return children;
	}
	public void setChildren(List<Fragment> children) {
		this.children = children;
	}
	
	@XmlAttribute
	public int getMaxLookAhead() {
		return maxLookAhead;
	}
	public void setMaxLookAhead(int maxLookAhead) {
		this.maxLookAhead = maxLookAhead;
	}

	/**
	 * This contains the "root" record which can be used to indicate a specific record if you are using named ones
	 */
	@XmlAttribute
	public String getRecord() {
		return record;
	}
	public void setRecord(String record) {
		this.record = record;
	}

	public FlatBindingConfig clone() {
		FlatBindingConfig config = new FlatBindingConfig();
		config.setChildren(getChildren());
		config.setComplexType(getComplexType());
		config.setMaxLookAhead(getMaxLookAhead());
		config.setRecord(getRecord());
		return config;
	}
	
	/**
	 * The "child separator" thing is too hard to do for two reasons:
	 * - because of the recursive parsing and the fact that the fragments have no link back to their parent, it is hard to ask for
	 * - last elements in a record probably don't need a separator, however this is hard to deduce with a generic "child separator" that is applicable to all
	 */
	@XmlRootElement(name = "record")
	public static class Record extends Fragment {		
		private List<Fragment> children = new ArrayList<Fragment>();
		private Integer maxOccurs;
		private Boolean allowPartial;
		private String name, parent, complexType;
		
		@XmlElements({
			@XmlElement(name = "record", type = Record.class),
			@XmlElement(name = "field", type = Field.class)
		})
		public List<Fragment> getChildren() {
			if (children == null) {
				children = new ArrayList<Fragment>();
			}
			return children;
		}
		public void setChildren(List<Fragment> children) {
			this.children = children;
		}
		
		@XmlAttribute
		public Integer getMaxOccurs() {
			return maxOccurs;
		}
		public void setMaxOccurs(Integer maxOccurs) {
			this.maxOccurs = maxOccurs;
		}
		
		@XmlTransient
		public boolean isPartialAllowed() {
			return allowPartial != null && allowPartial;
		}
		
		@XmlAttribute
		public Boolean getAllowPartial() {
			return allowPartial;
		}
		public void setAllowPartial(Boolean allowPartial) {
			this.allowPartial = allowPartial;
		}
		
		@XmlAttribute
		public String getName() {
			return name;
		}
		public void setName(String name) {
			this.name = name;
		}
		
		@XmlAttribute
		public String getParent() {
			return parent;
		}
		public void setParent(String parent) {
			this.parent = parent;
		}
		
		@XmlAttribute
		public String getComplexType() {
			return complexType;
		}
		public void setComplexType(String complexType) {
			this.complexType = complexType;
		}
		
		public Record resolve(List<Fragment> fragments) {
			if (getParent() == null) {
				return this;
			}
			Record clone = null;
			for (Fragment fragment : fragments) {
				if (fragment instanceof Record) {
					Record record = (Record) fragment;
					if (record.getName() != null && record.getName().equals(getParent())) {
						clone = record.resolve(fragments).clone();
						clone.merge(this);
						break;
					}
				}
			}
			if (clone == null) {
				throw new IllegalArgumentException("Can not find parent " + getParent());
			}
			return clone;
		}

		public void merge(Record record) {
			getChildren().addAll(record.getChildren());
			if (getLength() == null) {
				setLength(record.getLength());
			}
			if (getMaxLength() == null) {
				setMaxLength(record.getMaxLength());
			}
			if (getMaxOccurs() == null) {
				setMaxOccurs(record.getMaxOccurs());
			}
			if (getSeparator() == null) {
				setSeparator(record.getSeparator());
			}
			if (getSeparatorLength() == null) {
				setSeparatorLength(record.getSeparatorLength());
			}
			if (getAllowPartial() == null) {
				setAllowPartial(record.getAllowPartial());
			}
			if (getMap() == null) {
				setMap(record.getMap());
			}
			if (getComplexType() == null) {
				setComplexType(record.getComplexType());
			}
		}
		
		public Record clone() {
			Record record = new Record();
			record.setAllowPartial(isPartialAllowed());
			record.setDescription(getDescription());
			record.setMap(getMap());
			record.setLength(getLength());
			record.setMaxLength(getMaxLength());
			record.setMaxOccurs(getMaxOccurs());
			record.setChildren(new ArrayList<Fragment>(getChildren()));
			record.setSeparator(getSeparator());
			record.setSeparatorLength(getSeparatorLength());
			record.setComplexType(getComplexType());
			return record;
		}
	}
	
	@XmlRootElement(name = "field")
	public static class Field extends Fragment {
		private String fixed;
		private String match;
		private String pad;
		private boolean leftAlign, canEnd;
		private String formatter;
		private Map<QName, String> otherAttributes;
		
		@XmlAttribute
		public String getFixed() {
			return fixed;
		}
		public void setFixed(String fixed) {
			this.fixed = fixed;
		}
		@XmlAttribute
		public String getMatch() {
			return match;
		}
		public void setMatch(String match) {
			this.match = match;
		}
		@XmlAttribute
		public boolean isLeftAlign() {
			return leftAlign;
		}
		public void setLeftAlign(boolean leftAlign) {
			this.leftAlign = leftAlign;
		}
		@XmlAttribute
		public String getFormatter() {
			return formatter;
		}
		public void setFormatter(String formatter) {
			this.formatter = formatter;
		}
		@XmlAnyAttribute
		public Map<QName, String> getOtherAttributes() {
			return otherAttributes;
		}
		public void setOtherAttributes(Map<QName, String> otherAttributes) {
			this.otherAttributes = otherAttributes;
		}
		@XmlAttribute
		public boolean isCanEnd() {
			return canEnd;
		}
		public void setCanEnd(boolean canEnd) {
			this.canEnd = canEnd;
		}
		@XmlAttribute
		public String getPad() {
			return pad;
		}
		public void setPad(String pad) {
			this.pad = pad;
		}
	}
	
	@XmlSeeAlso({ Record.class, Field.class })
	public static class Fragment {
		private String separator;
		private Integer separatorLength;
		private String map;
		private Integer length;
		private Integer maxLength;
		private String description;

		@XmlAttribute
		public String getSeparator() {
			return separator;
		}
		public void setSeparator(String separator) {
			this.separator = separator;
		}
		@XmlAttribute
		public String getMap() {
			return map;
		}
		public void setMap(String map) {
			this.map = map;
		}
		@XmlAttribute
		public Integer getLength() {
			return length;
		}
		public void setLength(Integer length) {
			this.length = length;
		}
		@XmlAttribute
		public Integer getMaxLength() {
			return maxLength;
		}
		public void setMaxLength(Integer maxLength) {
			this.maxLength = maxLength;
		}
		@XmlAttribute
		public String getDescription() {
			return description;
		}
		public void setDescription(String description) {
			this.description = description;
		}
		@Override
		public String toString() {
			return getClass().getSimpleName() + "[" + (map == null ? description : map) + "]"; 
		}
		@XmlAttribute
		public Integer getSeparatorLength() {
			return separatorLength;
		}
		public void setSeparatorLength(Integer separatorLength) {
			this.separatorLength = separatorLength;
		}
	}
	
	public static FlatBindingConfig load(URL url) throws IOException {
		InputStream input = url.openStream();
		try {
			return load(input);
		}
		finally {
			input.close();
		}
	}
	public static FlatBindingConfig load(InputStream input) throws IOException {
		try {
			JAXBContext context = JAXBContext.newInstance(FlatBindingConfig.class, Record.class, Field.class);
			return (FlatBindingConfig) context.createUnmarshaller().unmarshal(input);
		}
		catch (JAXBException e) {
			throw new RuntimeException(e);
		}
	}
	@Override
	public String getComplexType() {
		String complexType = super.getComplexType();
		if (complexType == null && getRecord() != null) {
			for (Fragment child : getChildren()) {
				if (child instanceof Record && getRecord().equals(((Record) child).getName())) {
					complexType = ((Record) child).getComplexType();
					break;
				}
			}
		}
		return complexType;
	}
}
