import { LitElement, html, css } from 'lit';
import { JsonRpc } from 'jsonrpc';
import '@vaadin/button';

const SITE_KEY = 'quarkpress-dev-ui.site';

/** The site the pages show, kept in local storage; site 1 when none is kept or storage is unavailable. */
function currentSite() {
    try {
        const value = Number(localStorage.getItem(SITE_KEY));
        return Number.isInteger(value) && value > 0 ? value : 1;
    } catch (e) {
        return 1;
    }
}

function keepSite(id) {
    try {
        localStorage.setItem(SITE_KEY, String(id));
    } catch (e) {
        // storage unavailable: the choice lasts until the page is left
    }
}

/**
 * The current site, checked against the sites that exist: when the kept site is gone (the dev database was recreated,
 * say), site 1 is kept instead and the answer carries a notice saying so. When the check itself fails, the kept site
 * stays and the page's own read reports the problem.
 */
async function checkedSite(jsonRpc) {
    const site = currentSite();
    if (site === 1) {
        return { site: 1, notice: null };
    }
    try {
        const r = await jsonRpc.siteExists({ siteId: site });
        if (r.result === false) {
            keepSite(1);
            return { site: 1, notice: `Site ${site} no longer exists; showing site 1.` };
        }
    } catch (e) {
        // the page's read reports the problem
    }
    return { site: site, notice: null };
}

function errorText(e) {
    if (e != null && e.error != null && e.error.message != null) {
        return e.error.message;
    }
    return String(e);
}

/** The content of the current site: posts by type and status, terms by taxonomy and the number of revisions. */
export class QwcQuarkpressCounts extends LitElement {

    jsonRpc = new JsonRpc(this);

    static styles = css`
        :host { display: flex; flex-direction: column; gap: 10px; padding: 0 10px 10px 10px; }
        .error { color: var(--lumo-error-text-color); }
        .notice { color: var(--lumo-warning-text-color, var(--lumo-secondary-text-color)); }
        .none { color: var(--lumo-contrast-50pct); }
        table { border-collapse: collapse; font-size: var(--lumo-font-size-s); }
        th, td { border: 1px solid var(--lumo-contrast-10pct); padding: 2px 8px; text-align: left; }
        td.number { text-align: right; }
        .bar { display: flex; gap: 10px; align-items: baseline; }
    `;

    static properties = {
        _site: { state: true },
        _notice: { state: true },
        _counts: { state: true },
        _error: { state: true }
    };

    connectedCallback() {
        super.connectedCallback();
        this._notice = null;
        this._refresh();
    }

    _refresh() {
        this._site = currentSite();
        this._error = null;
        checkedSite(this.jsonRpc).then(checked => {
            this._site = checked.site;
            this._notice = checked.notice;
            this.jsonRpc.counts({ siteId: this._site })
                .then(r => { this._counts = r.result; })
                .catch(e => { this._counts = null; this._error = errorText(e); });
        });
    }

    render() {
        return html`
            ${this._notice == null ? '' : html`<div class="notice">${this._notice}</div>`}
            <div class="bar">
                <span>Site ${this._site}</span>
                <vaadin-button theme="small" @click="${() => this._refresh()}">Refresh</vaadin-button>
            </div>
            ${this._error == null ? '' : html`<div class="error">${this._error}</div>`}
            ${this._counts == null ? '' : this._content(this._counts)}`;
    }

    _content(counts) {
        return html`
            <h3>Posts</h3>
            ${this._posts(counts.posts == null ? {} : counts.posts)}
            ${counts.terms == null ? '' : html`<h3>Terms</h3>${this._terms(counts.terms)}`}
            ${counts.revisions == null ? '' : html`<h3>Revisions</h3><div>${counts.revisions}</div>`}`;
    }

    _posts(posts) {
        const types = Object.keys(posts);
        if (types.length === 0) {
            return html`<div class="none">No posts.</div>`;
        }
        const statusSet = new Set();
        for (const type of types) {
            for (const status of Object.keys(posts[type])) {
                statusSet.add(status);
            }
        }
        const statuses = [...statusSet].sort();
        return html`
            <table>
                <thead><tr><th>Type</th>${statuses.map(s => html`<th>${s}</th>`)}</tr></thead>
                <tbody>
                    ${types.map(type => html`
                        <tr><td>${type}</td>${statuses.map(s => html`<td class="number">${posts[type][s] == null ? '' : posts[type][s]}</td>`)}</tr>`)}
                </tbody>
            </table>`;
    }

    _terms(terms) {
        const taxonomies = Object.keys(terms);
        if (taxonomies.length === 0) {
            return html`<div class="none">No taxonomies.</div>`;
        }
        return html`
            <table>
                <thead><tr><th>Taxonomy</th><th>Terms</th></tr></thead>
                <tbody>${taxonomies.map(t => html`<tr><td>${t}</td><td class="number">${terms[t]}</td></tr>`)}</tbody>
            </table>`;
    }
}
customElements.define('qwc-quarkpress-counts', QwcQuarkpressCounts);
