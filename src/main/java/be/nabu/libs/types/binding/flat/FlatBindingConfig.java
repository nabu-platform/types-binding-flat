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
import javax.xml.namespace.QName;

import be.nabu.libs.types.binding.BindingConfig;

@XmlRootElement(name = "binding")
public class FlatBindingConfig extends BindingConfig {
	
	private List<Fragment> children;
	private int maxLookAhead = 1024000;
	
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
	 * The "child separator" thing is too hard to do for two reasons:
	 * - because of the recursive parsing and the fact that the fragments have no link back to their parent, it is hard to ask for
	 * - last elements in a record probably don't need a separator, however this is hard to deduce with a generic "child separator" that is applicable to all
	 */
	@XmlRootElement(name = "record")
	public static class Record extends Fragment {		
		private List<Fragment> children;
		private Integer maxOccurs;
		private boolean allowPartial = false;
		
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
		
		@XmlAttribute
		public boolean isAllowPartial() {
			return allowPartial;
		}
		public void setAllowPartial(boolean allowPartial) {
			this.allowPartial = allowPartial;
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
}
