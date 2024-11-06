/*
* Copyright (C) 2014 Alexander Verbruggen
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Lesser General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public License
* along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

package be.nabu.libs.types.binding.flat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.text.ParseException;

import junit.framework.TestCase;
import be.nabu.libs.types.DefinedTypeResolverFactory;
import be.nabu.libs.types.TypeUtils;
import be.nabu.libs.types.api.ComplexContent;
import be.nabu.libs.types.binding.api.Window;
import be.nabu.libs.types.java.BeanInstance;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.io.api.ByteBuffer;

public class TestFlat extends TestCase {
	
	public void testWindowedParse() throws IOException, ParseException {
		FlatBindingConfig config = FlatBindingConfig.load(Thread.currentThread().getContextClassLoader().getResource("binding.xml"));
		FlatBinding binding = new FlatBinding(DefinedTypeResolverFactory.getInstance().getResolver(), config, Charset.forName("UTF-8"));
		InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream("flat-input.csv");
		Company result = unmarshal(binding, "flat-input.csv", Company.class);
		validate(result);
		ByteBuffer buffer = IOUtils.newByteBuffer();
		binding.marshal(IOUtils.toOutputStream(buffer), new BeanInstance<Company>(result));
		System.out.println(IOUtils.toString(IOUtils.wrapReadable(buffer, binding.getCharset())));
	}
	
	public void testNoFooter() throws IOException, ParseException {
		FlatBindingConfig config = FlatBindingConfig.load(Thread.currentThread().getContextClassLoader().getResource("binding.xml"));
		FlatBinding binding = new FlatBinding(DefinedTypeResolverFactory.getInstance().getResolver(), config, Charset.forName("UTF-8"));
		try {
			unmarshal(binding, "flat-no-footer.csv", Company.class);
			fail("should fail");
		}
		catch (ParseException e) {
			// expected
		}
	}
	
	public void testPlainParse() throws IOException, ParseException {
		FlatBindingConfig config = FlatBindingConfig.load(Thread.currentThread().getContextClassLoader().getResource("binding.xml"));
		FlatBinding binding = new FlatBinding(DefinedTypeResolverFactory.getInstance().getResolver(), config, Charset.forName("UTF-8"));
		Company result = unmarshal(binding, "flat-input.csv", Company.class);
		validate(result);
	}
	
	public void testWrongHeader() throws IOException, ParseException {
		FlatBindingConfig config = FlatBindingConfig.load(Thread.currentThread().getContextClassLoader().getResource("binding.xml"));
		FlatBinding binding = new FlatBinding(DefinedTypeResolverFactory.getInstance().getResolver(), config, Charset.forName("UTF-8"));
		binding.setScopeMessages(true);
		try {
			unmarshal(binding, "flat-wrong-header.csv", Company.class);
			fail("Should fail");
		}
		catch (ParseException e) {
			System.out.println(binding.getMessages());
		}
	}
	
	public void testAge() throws IOException, ParseException {
		FlatBindingConfig config = FlatBindingConfig.load(Thread.currentThread().getContextClassLoader().getResource("binding.xml"));
		FlatBinding binding = new FlatBinding(DefinedTypeResolverFactory.getInstance().getResolver(), config, Charset.forName("UTF-8"));
		try {
			assertNull(unmarshal(binding, "flat-wrong-age.csv", Company.class));
			fail("Should fail because the footer is parsed 'correctly' (there are no validators) which leaves a trailing end that is not allowed");
		}
		catch(ParseException e) {
			// expected
			System.out.println(binding.getMessages());
		}
	}

	public void testMultipleParse() throws IOException, ParseException {
		// just parse the first company
		FlatBindingConfig config = FlatBindingConfig.load(Thread.currentThread().getContextClassLoader().getResource("complex-binding.xml"));
		FlatBinding binding = new FlatBinding(DefinedTypeResolverFactory.getInstance().getResolver(), config, Charset.forName("UTF-8"));
		config.setAllowTrailing(true);
		Company result = unmarshal(binding, "flat-multiple.csv", Company.class);
		validate(result);
		config.setAllowTrailing(false);
		// trying a single parse again should fail as there is a second company behind it
		try {
			unmarshal(binding, "flat-multiple.csv", Company.class);
			fail("Expecting an error");
		}
		catch (ParseException e) {
			assertTrue(e.getMessage().contains("Trailing"));
		}
		config.setAllowTrailing(true);
		// try the multiple parse
		Companies companies = unmarshal(binding.getNamedBinding("companies"), "flat-multiple.csv", Companies.class);
		assertEquals(2, companies.getCompanies().size());
		validate(companies.getCompanies().get(0));
		validate(companies.getCompanies().get(1));
	}
	
	
	public <T> T unmarshal(FlatBinding binding, String name, Class<T> beanType) throws IOException, ParseException {
		return unmarshal(binding, Thread.currentThread().getContextClassLoader().getResourceAsStream(name), beanType);
	}
	
	public <T> T unmarshal(FlatBinding binding, InputStream input, Class<T> beanType) throws IOException, ParseException {
		try {
			ComplexContent content = binding.unmarshal(input, new Window [0]);
			return content == null ? null : TypeUtils.getAsBean(content, beanType);
		}
		finally {
			input.close();
		}
	}
	
	private void validate(Company result) {
		validateHeader(result);
		assertEquals(24, result.getEmployees().size());
		assertEquals("John1", result.getEmployees().get(1).getFirstName());

		assertEquals(new Integer(31), result.getEmployees().get(0).getAge());
		assertEquals(new Integer(60), result.getEmployees().get(10).getAge());
		assertEquals(new Integer(44), result.getEmployees().get(14).getAge());
		
		assertEquals(new Integer(31), result.getEmployees().get(0).getAge());
		assertEquals(new Integer(57), result.getEmployees().get(1).getAge());
		
		// check that the last one is correctly reparsed
		assertEquals(new Integer(31), result.getEmployees().get(22).getAge());
		assertEquals(new Integer(31), result.getEmployees().get(23).getAge());
		validateFooter(result);
	}

	private void validateHeader(Company result) {
		assertEquals("Nabu", result.getName());
		assertEquals("Organizational", result.getUnit());
	}

	private void validateFooter(Company result) {
		assertEquals("Nabu HQ", result.getAddress());
		assertEquals("BE666-66-66", result.getBillingNumber());
	}
}
