package com.flipcopilot;

import lombok.Value;

/** One row of /mapping. */
@Value
public class ItemMeta
{
	int id;
	String name;
	int limit;
	boolean members;
}
