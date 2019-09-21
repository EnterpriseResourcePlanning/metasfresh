package org.adempiere.mm.attributes.api;

import static org.adempiere.model.InterfaceWrapperHelper.newInstance;
import static org.adempiere.model.InterfaceWrapperHelper.saveRecord;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.util.Optional;

import org.adempiere.mm.attributes.AttributeSetInstanceId;
import org.adempiere.mm.attributes.AttributeValueId;
import org.adempiere.mm.attributes.api.impl.AttributesTestHelper;
import org.adempiere.test.AdempiereTestHelper;
import org.adempiere.test.AdempiereTestWatcher;
import org.compiere.model.I_M_Attribute;
import org.compiere.model.I_M_AttributeSetInstance;
import org.compiere.model.I_M_AttributeValue;
import org.compiere.model.X_M_Attribute;
import org.junit.Rule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.rules.TestWatcher;

import de.metas.material.event.commons.AttributesKey;
import de.metas.material.event.commons.AttributesKeyPart;
import de.metas.util.Services;
import lombok.NonNull;

/*
 * #%L
 * de.metas.business
 * %%
 * Copyright (C) 2017 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

public class AttributesKeysTest
{
	/** Watches the current tests and dumps the database to console in case of failure */
	@Rule
	public final TestWatcher testWatcher = new AdempiereTestWatcher();

	private AttributesTestHelper attributesTestHelper;
	private IAttributeSetInstanceBL attributeSetInstanceBL;

	@BeforeEach
	public void init()
	{
		AdempiereTestHelper.get().init();
		attributesTestHelper = new AttributesTestHelper();
		attributeSetInstanceBL = Services.get(IAttributeSetInstanceBL.class);
	}

	@Test
	public void createAttributesKeyFromASIStorageAttributes()
	{
		final I_M_Attribute attr1 = createStorageRelevantAttribute("test1");
		final I_M_AttributeValue attributeValue1 = attributesTestHelper.createM_AttributeValue(attr1, "testValue1");

		final I_M_Attribute attr2 = createStorageRelevantAttribute("test2");
		final I_M_AttributeValue attributeValue2 = attributesTestHelper.createM_AttributeValue(attr2, "testValue2");

		final I_M_AttributeSetInstance asi = newInstance(I_M_AttributeSetInstance.class);
		saveRecord(asi);
		final AttributeSetInstanceId asiId1 = AttributeSetInstanceId.ofRepoId(asi.getM_AttributeSetInstance_ID());

		attributeSetInstanceBL.getCreateAttributeInstance(asi, attributeValue1);
		attributeSetInstanceBL.getCreateAttributeInstance(asi, attributeValue2);

		// invoke the method under test
		final Optional<AttributesKey> result = AttributesKeys.createAttributesKeyFromASIStorageAttributes(asiId1);
		assertThat(result).isPresent();

		final AttributesKey expectedResult = AttributesKey.ofAttributeValueIds(attributeValue1.getM_AttributeValue_ID(), attributeValue2.getM_AttributeValue_ID());
		assertThat(result).contains(expectedResult);
	}

	private final I_M_Attribute createStorageRelevantAttribute(@NonNull final String name)
	{
		final I_M_Attribute attribute = attributesTestHelper.createM_Attribute(name, X_M_Attribute.ATTRIBUTEVALUETYPE_List, true);
		attribute.setIsStorageRelevant(true);
		saveRecord(attribute);
		return attribute;
	}

	@Test
	public void createAttributeSetInstanceFromAttributesKey()
	{
		final I_M_Attribute attr1 = createStorageRelevantAttribute("test1");
		final I_M_AttributeValue attributeValue1 = attributesTestHelper.createM_AttributeValue(attr1, "testValue1");

		final I_M_Attribute attr2 = createStorageRelevantAttribute("test2");
		final I_M_AttributeValue attributeValue2 = attributesTestHelper.createM_AttributeValue(attr2, "testValue2");

		final AttributesKey attributesKey = AttributesKey.ofAttributeValueIds(
				attributeValue1.getM_AttributeValue_ID(),
				attributeValue2.getM_AttributeValue_ID());

		// invoke the method under test
		final AttributeSetInstanceId result = AttributesKeys.createAttributeSetInstanceFromAttributesKey(attributesKey);

		final Optional<AttributesKey> reloadedAttributesKey = AttributesKeys.createAttributesKeyFromASIStorageAttributes(result);
		assertThat(reloadedAttributesKey).isPresent().contains(attributesKey);
	}

	@Test
	public void test_toImmutableAttributeSet()
	{
		final I_M_Attribute attr1 = attributesTestHelper.createM_Attribute("attr1", X_M_Attribute.ATTRIBUTEVALUETYPE_List, true);
		final I_M_AttributeValue attributeValue1 = attributesTestHelper.createM_AttributeValue(attr1, "value1");

		final I_M_Attribute attr2 = attributesTestHelper.createM_Attribute("attr2", X_M_Attribute.ATTRIBUTEVALUETYPE_List, true);
		final I_M_AttributeValue attributeValue2 = attributesTestHelper.createM_AttributeValue(attr2, "value2");

		// invoke the method under test
		final AttributesKey attributesKey = AttributesKey.ofAttributeValueIds(attributeValue1.getM_AttributeValue_ID(), attributeValue2.getM_AttributeValue_ID());
		final ImmutableAttributeSet result = AttributesKeys.toImmutableAttributeSet(attributesKey);

		assertThat(result.getAttributeIds()).hasSize(2);

		assertThat(result.getAttributeValueIdOrNull("attr1")).isEqualTo(AttributeValueId.ofRepoId(attributeValue1.getM_AttributeValue_ID()));
		assertThat(result.getValue("attr1")).isEqualTo("value1");

		assertThat(result.getAttributeValueIdOrNull("attr2")).isEqualTo(AttributeValueId.ofRepoId(attributeValue2.getM_AttributeValue_ID()));
		assertThat(result.getValue("attr2")).isEqualTo("value2");
	}

	@Test
	public void test_ImmutableAttributeSet_to_AttributeKey_to_ImmutableAttributeSet()
	{
		final I_M_Attribute stringAttribute = attributesTestHelper.createM_Attribute("stringAttribute", X_M_Attribute.ATTRIBUTEVALUETYPE_StringMax40, true);
		final I_M_Attribute numberAttribute = attributesTestHelper.createM_Attribute("numberAttribute", X_M_Attribute.ATTRIBUTEVALUETYPE_Number, true);
		final I_M_Attribute dateAttribute = attributesTestHelper.createM_Attribute("dateAttribute", X_M_Attribute.ATTRIBUTEVALUETYPE_Date, true);
		final I_M_Attribute listAttribute = attributesTestHelper.createM_Attribute("listAttribute", X_M_Attribute.ATTRIBUTEVALUETYPE_List, true);
		final I_M_AttributeValue listAttributeValue1 = attributesTestHelper.createM_AttributeValue(listAttribute, "value1");

		final ImmutableAttributeSet attributeSet = ImmutableAttributeSet.builder()
				.attributeValue(stringAttribute, AttributesKeyPart.normalizeStringValue("stringValue"))
				.attributeValue(numberAttribute, AttributesKeyPart.normalizeNumberValue(new BigDecimal("12.345")))
				.attributeValue(dateAttribute, AttributesKeyPart.normalizeDateValue(LocalDate.of(2019, Month.SEPTEMBER, 21)))
				.attributeValue(listAttributeValue1)
				.build();

		final AttributesKey attributesKey = AttributesKeys.createAttributesKeyFromAttributeSet(attributeSet).orElse(null);
		final ImmutableAttributeSet attributeSet2 = AttributesKeys.toImmutableAttributeSet(attributesKey);

		assertThat(attributeSet2).isEqualTo(attributeSet);
	}
}
