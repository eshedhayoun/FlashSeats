/**
 * The failures {@code catalog}'s facade raises.
 *
 * <p>Published deliberately: global standards §5 requires a facade to throw only exceptions its own
 * module owns, which means callers must be able to see them. A caller lets these propagate — the one
 * global advice maps each to its registry code — rather than re-wrapping them.
 */
@org.springframework.modulith.NamedInterface("exception")
package com.flashseats.catalog.exception;
