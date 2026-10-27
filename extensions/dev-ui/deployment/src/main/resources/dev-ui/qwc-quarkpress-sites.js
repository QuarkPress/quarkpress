import { LitElement, html, css } from 'lit';
import { JsonRpc } from 'jsonrpc';
import { columnBodyRenderer } from '@vaadin/grid/lit.js';
import '@vaadin/grid';
import '@vaadin/button';
import '@vaadin/text-field';

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

/** A grid cell showing text that a narrow column may cut off, with the whole text as its tooltip. */
function full(text) {
    const value = text == null ? '' : String(text);
    return html`<span title="${value}">${value}</span>`;
}

/**
 * What a site's public setting means: 1 lets search engines index the site, 0 asks them not to, and any other value
 * (plugins use negative ones for private sites) is shown as it is.
 */
function publicText(flag) {
    if (flag === 1) {
        return 'yes';
    }
    if (flag === 0) {
        return 'no (asks search engines not to index)';
    }
    return `other (${flag})`;
}

function errorText(e) {
    if (e != null && e.error != null && e.error.message != null) {
        return e.error.message;
    }
    return String(e);
}

/** The sites of the network: choose the site the other pages show, and create a site. */
export class QwcQuarkpressSites extends LitElement {

    jsonRpc = new JsonRpc(this);

    static styles = css`
        :host { display: flex; flex-direction: column; gap: 10px; height: 100%; padding: 0 10px; }
        vaadin-grid { flex: 1; min-height: 200px; }
        .form { display: flex; gap: 10px; align-items: baseline; flex-wrap: wrap; }
        .error { color: var(--lumo-error-text-color); }
        .notice { color: var(--lumo-warning-text-color, var(--lumo-secondary-text-color)); }
        .current { font-weight: bold; }
    `;

    static properties = {
        _sites: { state: true },
        _error: { state: true },
        _created: { state: true },
        _current: { state: true },
        _notice: { state: true },
        _domain: { state: true },
        _path: { state: true },
        _title: { state: true }
    };

    constructor() {
        super();
        this._sites = [];
        this._error = null;
        this._created = null;
        this._domain = '';
        this._path = '/';
        this._title = '';
        this._notice = null;
    }

    connectedCallback() {
        super.connectedCallback();
        this._current = currentSite();
        checkedSite(this.jsonRpc).then(checked => {
            this._current = checked.site;
            this._notice = checked.notice;
        });
        this._load();
    }

    _load() {
        this._error = null;
        this.jsonRpc.sites()
            .then(r => { this._sites = r.result == null ? [] : r.result; })
            .catch(e => { this._error = errorText(e); });
    }

    render() {
        return html`
            ${this._notice == null ? '' : html`<div class="notice">${this._notice}</div>`}
            <div>Current site: <span class="current">${this._current}</span></div>
            <vaadin-grid .items="${this._sites}" theme="no-border row-stripes">
                <vaadin-grid-column header="" auto-width
                    ${columnBodyRenderer(s => this._useButton(s), [this._current])}></vaadin-grid-column>
                <vaadin-grid-column header="Id" auto-width
                    ${columnBodyRenderer(s => html`${s.id}`, [])}></vaadin-grid-column>
                <vaadin-grid-column header="Domain" auto-width resizable
                    ${columnBodyRenderer(s => full(s.domain), [])}></vaadin-grid-column>
                <vaadin-grid-column header="Path" auto-width
                    ${columnBodyRenderer(s => full(s.path), [])}></vaadin-grid-column>
                <vaadin-grid-column header="Name" auto-width resizable
                    ${columnBodyRenderer(s => full(s.name), [])}></vaadin-grid-column>
                <vaadin-grid-column header="Status" auto-width
                    ${columnBodyRenderer(s => html`${s.status}`, [])}></vaadin-grid-column>
                <vaadin-grid-column header="Public" auto-width
                    ${columnBodyRenderer(s => html`${publicText(s.publicFlag)}`, [])}></vaadin-grid-column>
                <vaadin-grid-column header="Flags" auto-width
                    ${columnBodyRenderer(s => html`${this._flags(s)}`, [])}></vaadin-grid-column>
            </vaadin-grid>
            <div class="form">
                <vaadin-text-field label="Domain" .value="${this._domain}"
                    @value-changed="${e => { this._domain = e.detail.value; }}"></vaadin-text-field>
                <vaadin-text-field label="Path" .value="${this._path}"
                    @value-changed="${e => { this._path = e.detail.value; }}"></vaadin-text-field>
                <vaadin-text-field label="Title" .value="${this._title}"
                    @value-changed="${e => { this._title = e.detail.value; }}"></vaadin-text-field>
                <vaadin-button theme="primary" @click="${() => this._create()}">Create site</vaadin-button>
            </div>
            ${this._created == null ? '' : html`<div>${this._created}</div>`}
            ${this._error == null ? '' : html`<div class="error">${this._error}</div>`}`;
    }

    _useButton(site) {
        if (site.id === this._current) {
            return html`<span class="current">current</span>`;
        }
        return html`<vaadin-button theme="small" @click="${() => this._use(site.id)}">Use</vaadin-button>`;
    }

    _use(id) {
        this._created = null;
        this._notice = null;
        keepSite(id);
        this._current = id;
    }

    _flags(site) {
        const flags = [];
        if (site.archived) flags.push('archived');
        if (site.mature) flags.push('mature');
        if (site.spam) flags.push('spam');
        if (site.deleted) flags.push('deleted');
        return flags.join(', ');
    }

    _create() {
        const domain = this._domain == null ? '' : this._domain.trim();
        const path = this._path == null ? '' : this._path.trim();
        const title = this._title == null ? '' : this._title.trim();
        this._created = null;
        if (domain === '' || path === '' || title === '') {
            this._error = 'Domain, path and title are required.';
            return;
        }
        this._error = null;
        this.jsonRpc.createSite({ domain: domain, path: path, title: title })
            .then(r => {
                if (r.result == null) {
                    this._error = 'No answer.';
                } else if (r.result.error != null) {
                    this._error = r.result.error;
                } else {
                    this._title = '';
                    this._load();
                    this._created = `Site ${r.result.site.id} created`;
                }
            })
            .catch(e => { this._error = errorText(e); });
    }
}
customElements.define('qwc-quarkpress-sites', QwcQuarkpressSites);
