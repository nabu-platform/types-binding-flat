package be.nabu.libs.types.binding.flat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.xml.bind.annotation.adapters.XmlAdapter;
import javax.xml.namespace.QName;

import be.nabu.libs.converter.ConverterFactory;
import be.nabu.libs.converter.api.Converter;
import be.nabu.libs.property.api.Property;
import be.nabu.libs.property.api.Value;
import be.nabu.libs.resources.api.ReadableResource;
import be.nabu.libs.types.CollectionHandlerFactory;
import be.nabu.libs.types.DefinedTypeResolverFactory;
import be.nabu.libs.types.api.CollectionHandler;
import be.nabu.libs.types.api.CollectionHandlerProvider;
import be.nabu.libs.types.api.ComplexContent;
import be.nabu.libs.types.api.ComplexType;
import be.nabu.libs.types.api.DefinedTypeResolver;
import be.nabu.libs.types.api.Element;
import be.nabu.libs.types.api.MarshalException;
import be.nabu.libs.types.api.Marshallable;
import be.nabu.libs.types.api.Unmarshallable;
import be.nabu.libs.types.base.ValueImpl;
import be.nabu.libs.types.binding.BaseConfigurableTypeBinding;
import be.nabu.libs.types.binding.api.PartialUnmarshaller;
import be.nabu.libs.types.binding.api.Window;
import be.nabu.libs.types.binding.api.WindowedList;
import be.nabu.libs.types.binding.flat.FlatBindingConfig.Field;
import be.nabu.libs.types.binding.flat.FlatBindingConfig.Fragment;
import be.nabu.libs.types.binding.flat.FlatBindingConfig.Record;
import be.nabu.libs.types.java.BeanInstance;
import be.nabu.libs.types.properties.MaxOccursProperty;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.io.api.CharBuffer;
import be.nabu.utils.io.api.CountingReadableContainer;
import be.nabu.utils.io.api.CountingWritableContainer;
import be.nabu.utils.io.api.DelimitedCharContainer;
import be.nabu.utils.io.api.MarkableContainer;
import be.nabu.utils.io.api.ReadableContainer;
import be.nabu.utils.io.api.WritableContainer;
import be.nabu.utils.io.containers.EOFReadableContainer;

public class FlatBinding extends BaseConfigurableTypeBinding<FlatBindingConfig> {

	private CollectionHandler collectionHandler = CollectionHandlerFactory.getInstance().getHandler();
	private Converter converter = ConverterFactory.getInstance().getConverter();
	private ReadableResource resource;
	private Charset charset;
	
	public FlatBinding(FlatBindingConfig config, Charset charset) {
		this(DefinedTypeResolverFactory.getInstance().getResolver(), config, charset);
	}
	
	public FlatBinding(DefinedTypeResolver definedTypeResolver, FlatBindingConfig config, Charset charset) {
		super(definedTypeResolver, config);
		this.charset = charset;
	}
	
	public FlatBinding getNamedBinding(String name) {
		FlatBindingConfig clone = null;
		for (Fragment child : getConfig().getChildren()) {
			if (child instanceof Record && name.equals(((Record) child).getName())) {
				clone = getConfig().clone();
				clone.setRecord(name);
				if (((Record) child).getComplexType() != null) {
					clone.setComplexType(((Record) child).getComplexType());
				}
			}
		}
		if (clone == null) {
			throw new IllegalArgumentException("No binding found with the name: " + name);
		}
		return new FlatBinding(clone, getCharset());
	}
	
	@Override
	protected ComplexContent unmarshal(ReadableResource resource, ComplexType type, Window [] windows, Value<?>...values) throws IOException, ParseException {
		this.resource = resource;
		ReadableContainer<CharBuffer> readable = IOUtils.wrapReadable(resource.getReadable(), charset);
		ComplexContent content = type.newInstance();
		Record record = new Record();
		if (getConfig().getRecord() != null) {
			for (Fragment child : getConfig().getChildren()) {
				if (child instanceof Record && getConfig().getRecord().equals(((Record) child).getName())) {
					record.getChildren().add(child);
					break;
				}
			}
		}
		else {
			record.setChildren(getConfig().getChildren());
		}
		if (unmarshal(type.getName(), IOUtils.countReadable(readable), record, content, windows) == 0) {
			return content;
		}
		else {
			return null;
		}
	}
	
	private String normalizeSeparator(String separator) {
		return separator.replace("\\n", "\n")
			.replace("\\r", "\r");
	}
	
	/**
	 * The return value is the amount the parent must reset to try the next bit (only for partial matches)
	 * So if we have a 0 reset, we have matched the parent exactly
	 * If we have a -1, we need to reset to the beginning, nothing positive was read
	 * If some positive, some negative, it will be an amount to reset
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	protected long unmarshal(String path, CountingReadableContainer<CharBuffer> input, Fragment fragment, ComplexContent content, Window...windows) throws IOException, ParseException {		
		// the amount to reset in the parent marked container because it was not matched in a trailing fashion
		long resetAmount = 0;
		
		ReadableContainer<CharBuffer> readable = null;
		DelimitedCharContainer delimited = null;

		// make sure we register eofs
		// the fields simply do a toString() which will be empty if the stream is done, this means it would keep going indefinately otherwise
		EOFReadableContainer<CharBuffer> eof = new EOFReadableContainer<CharBuffer>(input);
		// separator based
		if (fragment.getSeparator() != null) {
			String separator = normalizeSeparator(fragment.getSeparator());
		
			// we need a maxlength to scan for
			Integer maxLength = fragment.getMaxLength() != null ? fragment.getMaxLength() : getConfig().getMaxLookAhead();
			
			// limit in size & separator
			if (fragment.getSeparatorLength() == null) {
				delimited = IOUtils.delimit(IOUtils.limitReadable(eof, maxLength), separator);
			}
			else {
				delimited = IOUtils.delimit(IOUtils.limitReadable(eof, maxLength), separator, fragment.getSeparatorLength());
			}
				
			readable = delimited;
		}
		// fixed length
		else if (fragment.getLength() != null) {
			readable = IOUtils.limitReadable(eof, fragment.getLength());
		}
		// not all fields must be delimited
		// for example child fields are naturally delimited by the record they belong to
		// root records don't need delimits because they might go to the end etc
		else {
			readable = eof;
		}
		
		if (fragment instanceof Record) {
			// need a correct offset to be able to skip later on
			long alreadyRead = input.getReadTotal();
			// need a correct offset to check the full length read later on
			long initialRead = alreadyRead;
			// mark the input so we can return to this point in case it is not the correct record
			MarkableContainer<CharBuffer> markable = IOUtils.mark(readable);
			markable.mark();
			
			// check to see that it has at least one match
			boolean hasAnyMatch = false;
			// now that we have a limited view, we need to parse the child fragments and hope there are identifying fields that can tell us if it is ok
			record: for (Fragment child : ((Record) fragment).getChildren()) {
				if (eof.isEOF()) {
					break;
				}
				// if the child is a record and it has a map, we need to create a new complex content
				if (child instanceof Record && child.getMap() != null) {
					child = ((Record) child).resolve(getConfig().getChildren());

					Element<?> childElement = content.getType().get(child.getMap());
					if (childElement == null) {
						throw new ParseException("The element " + child.getMap() + " does not exist in " + path, 0);
					}
					if (!(childElement.getType() instanceof ComplexType)) {
						throw new ParseException("The record points to a child that is not complex", 0);
					}
					Value<Integer> maxOccurs = childElement.getProperty(new MaxOccursProperty());
					int recordCounter = 0;
					// apart from the max occurs that limits the target data structure, you can also limit the amount of records in the source
					// for instance if you know a certain type of record will only occur x times, it might be best to tell the parser
					int maxRecordAmount = ((Record) child).getMaxOccurs() == null ? 0 : ((Record) child).getMaxOccurs();
					while(maxRecordAmount == 0 || recordCounter < maxRecordAmount) {
						if (eof.isEOF()) {
							break record;
						}
						ComplexContent childContent = ((ComplexType) childElement.getType()).newInstance();
						// if we successfully parsed the child, add it
						CountingReadableContainer<CharBuffer> childContainer = IOUtils.countReadable(markable, alreadyRead);
						String childPath = path + "/" + childElement.getName();
						resetAmount = unmarshal(childPath, childContainer, child, childContent, windows);
						// if we have done a full read (0) or a partial one (> 0) and it's allowed, continue
						if (resetAmount == 0 || (resetAmount > 0 && ((Record) child).isPartialAllowed())) {
							hasAnyMatch = true;
							// if we have to reset a number of characters, do this
							if (resetAmount > 0) {
								markable.reset();
								IOUtils.skipChars(markable, (childContainer.getReadTotal() - alreadyRead) - resetAmount);
								resetAmount = 0;
							}
							// otherwise just reset the mark
							else {
								// unmark the input, we have identified the part so we no longer need to buffer it for reset
								markable.unmark();
								// but mark it again for the next parse
								markable.mark();
							}
							// now we need to set it in the parent
							// if it is a list, it can be windowed!
							if (maxOccurs != null && maxOccurs.getValue() != 1) {
								// get the current value, see if there is a list already basically
								// Note: this only works with integer indexed collections
								Object currentObject = content.get(childElement.getName());
								int index = 0;
								if (currentObject != null) {
									CollectionHandlerProvider provider = collectionHandler.getHandler(currentObject.getClass());
									index = provider.getAsCollection(currentObject).size();
								}
								Window activeWindow = null;
								for (Window window : windows) {
									if (window.getPath().equals(childPath)) {
										activeWindow = window;
										break;
									}
								}
								if (activeWindow != null) {
									WindowedList list = null;
									// if the current object is already a list but it is empty (e.g. default initialization), overwrite it with a windowed list
									if (currentObject == null || (currentObject instanceof List && ((List) currentObject).isEmpty())) { 
										list = new WindowedList(resource, activeWindow, new PartialFlatUnmarshaller((Record) child, (ComplexType) content.getType().get(childElement.getName()).getType(), activeWindow, windows));
										content.set(childElement.getName(), list);
									}
									else if (currentObject instanceof WindowedList) {
										list = (WindowedList) currentObject;
									}
									else {
										throw new IllegalArgumentException("The collection already exists and is not windowed");
									}
									// always register the offset
									list.setOffset(index, alreadyRead);
									// only register the object if it is within the window size
									if (index < activeWindow.getSize()) {
										content.set(childElement.getName() + "[" + index + "]", childContent);
									}
								}
								else {
									// this reuses the internal collection handling
									content.set(childElement.getName() + "[" + index + "]", childContent);
								}
							}
							else {
								content.set(child.getMap(), childContent);
							}
							// we need to update the alreadyRead because this was successful
							alreadyRead = childContainer.getReadTotal();
							if (maxOccurs == null || maxOccurs.getValue() == 1) {
								break;
							}
							else {
								recordCounter++;
							}
						}
						// if not matched, reset for the next record
						else {
							// if partials are not allowed for this record and no records were matched, break
							// also note that if recordCounter is larger than 0, at least one full match was done so don't break fully in that case
							if (!((Record) fragment).isPartialAllowed() && recordCounter == 0) {
								if (recordCounter > 0) {
									resetAmount = childContainer.getReadTotal() - alreadyRead;
								}
								else {
									resetAmount = -1;
								}
								break record;
							}
							else {
								resetAmount = childContainer.getReadTotal() - alreadyRead;
								markable.reset();
								break;
							}
						}
					}
				}
				// it's either a record we don't need to map or a field
				else {
					CountingReadableContainer<CharBuffer> childContainer = IOUtils.countReadable(markable, alreadyRead);
					resetAmount = unmarshal(path, childContainer, child, content, windows);
					if (resetAmount == 0) {
						hasAnyMatch = true;
						markable.unmark();
						markable.mark();
						alreadyRead = childContainer.getReadTotal();
					}
					else if (resetAmount > 0 && child instanceof Record && ((Record) child).isPartialAllowed()) {
						markable.reset();
						IOUtils.skipChars(markable, (childContainer.getReadTotal() - alreadyRead) - resetAmount);
						resetAmount = 0;
					}
					else {
						resetAmount = -1;
						break;
					}
				}
			}
			// if not a single match was performed, make sure it is set to -1
			if (!hasAnyMatch) {
				resetAmount = -1;
			}
			// if the reset amount is already -1, just let it bubble up
			// if it is already positive, it doesn't matter, a reset will happen anyway
			// however if we think it's a full match, do additional checks to see that we read everything according to the definition
			if (resetAmount == 0) {
				// if we have a delimited stream, read it fully to see if we have dangling characters
				if (delimited != null) {
					// if we get here, it is possible the record was not read to the fullest (e.g. fixed length)
					String remainder = IOUtils.toString(markable);
					if (!remainder.isEmpty()) {
						throw new ParseException("There are dangling characters at the end of a record: '" + remainder + "'", 0);
					}
					resetAmount = input.getReadTotal() - alreadyRead;
					// the "alreadyread" is only for the valid record, it does not take into account any delimiters that are read, so make sure we take that into account
					if (delimited.isDelimiterFound()) {
						resetAmount -= delimited.getMatchedDelimiter().length();
					}
					// if the reset amount is still 0, we have confirmed the delimiter check
					if (resetAmount == 0 && fragment.getLength() != null) {
						long shouldHaveRead = fragment.getLength();
						if (delimited.isDelimiterFound()) {
							shouldHaveRead += delimited.getMatchedDelimiter().length();
						}
						if (shouldHaveRead != input.getReadTotal() - initialRead) {
							throw new ParseException("There were not enough characters for the record: " + (input.getReadTotal() - initialRead) + "/" + shouldHaveRead, 0);
						}
					}
				}
				// otherwise, if we have a fixed length, double check that
				else if (fragment.getLength() != null) {
					if (fragment.getLength() != ((CountingReadableContainer) readable).getReadTotal()) {
						throw new ParseException("Record of wrong length: " +  ((CountingReadableContainer) readable).getReadTotal() + "/" + fragment.getLength(), 0);
					}
				}
			}
		}
		// for a field, parse it and set it
		else {
			Field field = (Field) fragment;
			String value = IOUtils.toString(readable);
			if (delimited != null && !delimited.isDelimiterFound() && !field.isCanEnd()) {
				return -1;
			}
			else if (field.getFixed() != null && !field.getFixed().equals(value)) {
				return -1;
			}
			else if (field.getMatch() != null && !value.matches(field.getMatch())) {
				return -1;
			}
			else {
				resetAmount = 0;
			}
			if (field.getMap() != null) {
				// if it's fixed length, it might be padded
				if (field.getLength() != null) {
					String pad = field.getPad() == null ? " " : field.getPad();
					if (field.isLeftAlign()) {
						while (value.endsWith(pad)) {
							value = value.substring(0, value.length() - pad.length());
						}
					}
					else {
						while (value.startsWith(pad)) {
							value = value.substring(pad.length());
						}
					}
				}
				
				Object unmarshalledValue = value;
				if (value.isEmpty()) {
					unmarshalledValue = null;
				}
				// check if we want to use a formatter
				else if (field.getFormatter() != null) {
					try {
						Object formatterInstance = Thread.currentThread().getContextClassLoader().loadClass(field.getFormatter()).newInstance();
						if (formatterInstance instanceof Unmarshallable) {
							Unmarshallable<?> unmarshallable = (Unmarshallable<?>) formatterInstance;
							List<Value<?>> values = new ArrayList<Value<?>>();
							for (Property<?> property : unmarshallable.getSupportedProperties()) {
								QName qname = new QName(property.getName());
								if (field.getOtherAttributes() != null && field.getOtherAttributes().containsKey(qname)) {
									values.add(new ValueImpl(property, converter.convert(field.getOtherAttributes().get(qname), property.getValueClass())));
								}
							}
							unmarshalledValue = unmarshallable.unmarshal(value, values.toArray(new Value[0]));
						}
						else if (formatterInstance instanceof XmlAdapter) {
							XmlAdapter adapter = (XmlAdapter) formatterInstance;
							try {
								unmarshalledValue = adapter.unmarshal(value);
							}
							catch (Exception e) {
								throw new ParseException("The formatter " + field.getFormatter() + " failed to unmarshal the value: " + e.getMessage(), 0);
							}
						}
						else {
							throw new ParseException("Unknown unmarshaller: " + field.getFormatter(), 0);
						}
					}
					catch (InstantiationException e) {
						throw new ParseException("Can not instantiate formatter " + field.getFormatter(), 0);
					}
					catch (IllegalAccessException e) {
						throw new ParseException("Can not instantiate formatter " + field.getFormatter(), 0);
					}
					catch (ClassNotFoundException e) {
						throw new ParseException("Can not find formatter " + field.getFormatter(), 0);
					}
				}
				// if no custom formatter is used, the default conversion logic will be used
				content.set(field.getMap(), unmarshalledValue);
			}
		}
		return resetAmount;
	}

	@Override
	public void marshal(OutputStream output, ComplexContent content, Value<?>...values) throws IOException {
		Record record = new Record();
		if (getConfig().getRecord() != null) {
			for (Fragment child : getConfig().getChildren()) {
				if (child instanceof Record && getConfig().getRecord().equals(((Record) child).getName())) {
					record.getChildren().add(child);
					break;
				}
			}
		}
		else {
			record.setChildren(getConfig().getChildren());
		}
		marshal(IOUtils.wrapWritable(IOUtils.wrap(output), charset), record, content);
	}
	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private void marshal(WritableContainer<CharBuffer> output, Fragment fragment, ComplexContent content) throws IOException, MarshalException {
		if (fragment instanceof Record) {
			if (fragment.getMap() != null) {
				Object object = content.get(fragment.getMap());
				if (object != null) {
					Element<?> definition = content.getType().get(fragment.getMap());
					Value<Integer> maxOccurs = definition.getProperty(new MaxOccursProperty());
					// it's a list, we need to loop
					if (maxOccurs != null && maxOccurs.getValue() != 1) {
						CollectionHandlerProvider provider = collectionHandler.getHandler(object.getClass());
						for (Object child : provider.getAsCollection(object)) {
							if (!(child instanceof ComplexContent)) {
								child = new BeanInstance(child);
							}
							marshalRecord(output, (Record) fragment, (ComplexContent) child);
						}
					}
					else {
						if (!(object instanceof ComplexContent)) {
							object = new BeanInstance(object);
						}
						marshalRecord(output, (Record) fragment, (ComplexContent) object);
					}
				}
			}
			else {
				marshalRecord(output, (Record) fragment, content);
			}
		}
		// you can have fixed fields not mapped from the source
		else if (((Field) fragment).getFixed() != null) {
			String value = ((Field) fragment).getFixed();
			if (fragment.getSeparator() != null) {
				value += normalizeSeparator(fragment.getSeparator());
			}
			output.write(IOUtils.wrap(value));
		}
		// this will map the fields that are mapped from the source or are basically not mapped at all (like a spaceholder for fixed length fields that are not mapped)
		else {
			Field field = (Field) fragment;
			Object object = fragment.getMap() != null ? content.get(fragment.getMap()) : null;
			String mappedValue = null;
			if (object != null && field.getFormatter() != null) {
				try {
					Object formatterInstance = Thread.currentThread().getContextClassLoader().loadClass(field.getFormatter()).newInstance();
					if (formatterInstance instanceof Marshallable) {
						Marshallable marshallable = (Marshallable<?>) formatterInstance;
						List<Value<?>> values = new ArrayList<Value<?>>();
						for (Property<?> property : marshallable.getSupportedProperties()) {
							QName qname = new QName(property.getName());
							if (field.getOtherAttributes() != null && field.getOtherAttributes().containsKey(qname)) {
								values.add(new ValueImpl(property, converter.convert(field.getOtherAttributes().get(qname), property.getValueClass())));
							}
						}
						mappedValue = marshallable.marshal(object, values.toArray(new Value[0]));
					}
					else if (formatterInstance instanceof XmlAdapter) {
						XmlAdapter adapter = (XmlAdapter) formatterInstance;
						try {
							mappedValue = (String) adapter.marshal(object);
						}
						catch (Exception e) {
							throw new MarshalException("The formatter " + field.getFormatter() + " failed to unmarshal the value: " + e.getMessage(), e);
						}
					}
					else {
						throw new MarshalException("Unknown marshaller: " + field.getFormatter());
					}
				}
				catch (InstantiationException e) {
					throw new MarshalException("Can not instantiate formatter " + field.getFormatter(), e);
				}
				catch (IllegalAccessException e) {
					throw new MarshalException("Can not instantiate formatter " + field.getFormatter(), e);
				}
				catch (ClassNotFoundException e) {
					throw new MarshalException("Can not find formatter " + field.getFormatter(), e);
				}
			}
			// otherwise, if the object is not null, use default conversion
			else if (object != null) {
				mappedValue = converter.convert(object, String.class);
			}
			else {
				mappedValue = "";
			}
			if (fragment.getLength() != null) {
				String pad = field.getPad() == null ? " " : field.getPad();
				while (mappedValue.length() < fragment.getLength()) {
					if (field.isLeftAlign()) {
						mappedValue += pad;
					}
					else {
						mappedValue = pad + mappedValue;
					}
				}
				// this can happen if the pad is too long
				if (mappedValue.length() > fragment.getLength()) {
					if (field.isLeftAlign()) {
						mappedValue = mappedValue.substring(0, fragment.getLength());
					}
					else {
						mappedValue = mappedValue.substring(mappedValue.length() - fragment.getLength());
					}
				}
			}
			if (fragment.getSeparator() != null) {
				mappedValue += normalizeSeparator(fragment.getSeparator());
			}
			output.write(IOUtils.wrap(mappedValue));
		}
	}
	
	private void marshalRecord(WritableContainer<CharBuffer> output, Record record, ComplexContent content) throws IOException {
		CountingWritableContainer<CharBuffer> counted = IOUtils.countWritable(output);
		for (Fragment childFragment : record.getChildren()) {
			if (childFragment instanceof Record) {
				childFragment = ((Record) childFragment).resolve(getConfig().getChildren());
			}
			marshal(counted, childFragment, content);
		}
		if (record.getSeparator() != null) {
			output.write(IOUtils.wrap(normalizeSeparator(record.getSeparator())));
		}
		else if (record.getLength() != null) {
			for (long i = counted.getWrittenTotal(); i < record.getLength(); i++) {
				output.write(IOUtils.wrap(" "));
			}
		}
	}

	public Charset getCharset() {
		return charset;
	}
	
	public class PartialFlatUnmarshaller implements PartialUnmarshaller {

		private Record record;
		private ComplexType type;
		private Window thisWindow;
		private List<Window> otherWindows;
		
		public PartialFlatUnmarshaller(Record record, ComplexType type, Window thisWindow, Window...allWindows) {
			this.record = record;
			this.type = type;
			this.thisWindow = thisWindow;
			// make sure we remove the active window, otherwise the path will be reused for windowing!
			this.otherWindows = new ArrayList<Window>(Arrays.asList(allWindows));
			this.otherWindows.remove(thisWindow);
		}
		
		@Override
		public List<ComplexContent> unmarshal(InputStream input, long offset, int batchSize) throws IOException, ParseException {
			ReadableContainer<CharBuffer> readable = IOUtils.wrapReadable(IOUtils.wrap(input), charset);
			if (IOUtils.copyChars(readable, IOUtils.newCharSink(offset)) != offset) {
				throw new IOException("Could not skip to position " + offset);
			}
			List<ComplexContent> entries = new ArrayList<ComplexContent>();
			for (int i = 0; i < batchSize; i++) {
				CountingReadableContainer<CharBuffer> counting = IOUtils.countReadable(readable, offset);
				ComplexContent content = type.newInstance();
				FlatBinding.this.unmarshal(thisWindow.getPath(), counting, record, content, otherWindows.toArray(new Window[0]));
				entries.add(content);
				offset = counting.getReadTotal();
			}
			return entries;
		}
		
	}
}