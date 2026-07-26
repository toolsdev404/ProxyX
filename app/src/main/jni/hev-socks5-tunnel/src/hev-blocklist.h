/*
 ============================================================================
 Name        : hev-blocklist.h
 Description : Domain blocklist / allowlist for the mapped DNS resolver (ProxyX)
 ============================================================================
 */

#ifndef __HEV_BLOCKLIST_H__
#define __HEV_BLOCKLIST_H__

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Load the blocklist and allowlist from the given file paths. Either path may be
 * NULL or point to a missing/empty file, in which case that set is simply empty
 * (fail-open: nothing is blocked). Replaces any previously loaded data. Intended
 * to be called once, before DNS queries start being handled.
 *
 * File format: one domain per line. Blank lines and lines starting with '#' are
 * ignored. Hosts-style lines ("0.0.0.0 ads.example.com") are also accepted.
 */
void hev_blocklist_load (const char *block_path, const char *allow_path);

/*
 * Returns 1 if the given domain should be blocked: it (or a parent domain) is on
 * the blocklist AND is not covered by the allowlist. Matching is case-insensitive
 * and label-boundary based, so "ads.example.com" matches "x.ads.example.com" but
 * not "notads.example.com". The allowlist always wins over the blocklist.
 */
int hev_blocklist_is_blocked (const char *name);

/* Free all loaded data. */
void hev_blocklist_unload (void);

#ifdef __cplusplus
}
#endif

#endif /* __HEV_BLOCKLIST_H__ */