import { LitElement, html, css } from 'lit';
import { live } from 'lit/directives/live.js';
import { JsonRpc } from 'jsonrpc';
import { columnBodyRenderer } from '@vaadin/grid/lit.js';
import '@vaadin/grid';
import '@vaadin/checkbox';
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

/** A grid cell showing text that a narrow column may cut off, with the whole text as its tooltip. */
function full(text) {
    const value = text == null ? '' : String(text);
    return html`<span title="${value}">${value}</span>`;
}

function errorText(e) {
    if (e != null && e.error != null && e.error.message != null) {
        return e.error.message;
    }
    return String(e);
}

/**
 * The per-site features of the current site: their effective values, where each comes from, a switch each, and a
 * Reset that removes the site's own switch.
 */
export class QwcQuarkpressFeatures extends LitElement {

    jsonRpc = new JsonRpc(this);

    static styles = css`
        :host { display: flex; flex-direction: column; gap: 10px; height: 100%; padding: 0 10px; }
        vaadin-grid { flex: 1; }
        .error { color: var(--lumo-error-text-color); }
        .notice { color: var(--lumo-warning-text-color, var(--lumo-secondary-text-color)); }
    `;

    static properties = {
        _site: { state: true },
        _notice: { state: true },
        _features: { state: true },
        _loaded: { state: true },
        _error: { state: true },
        _message: { state: true },
        _busy: { state: true }
    };

    constructor() {
        super();
        this._features = [];
        this._loaded = false;
        this._error = null;
        this._message = null;
        this._busy = false;
        this._notice = null;
    }

    connectedCallback() {
        super.connectedCallback();
        this._site = currentSite();
        checkedSite(this.jsonRpc).then(checked => {
            this._site = checked.site;
            this._notice = checked.notice;
            this._load();
        });
    }

    _load() {
        this.jsonRpc.features({ siteId: this._site })
            .then(r => { this._features = r.result == null ? [] : r.result; this._loaded = true; })
            .catch(e => { this._features = []; this._error = errorText(e); });
    }

    render() {
        return html`
            ${this._notice == null ? '' : html`<div class="notice">${this._notice}</div>`}
            <div>Site ${this._site}</div>
            <vaadin-grid .items="${this._features}" theme="no-border row-stripes">
                <vaadin-grid-column header="Key" auto-width
                    ${columnBodyRenderer(f => full(f.key), [])}></vaadin-grid-column>
                <vaadin-grid-column header="Module" auto-width
                    ${columnBodyRenderer(f => full(f.module), [])}></vaadin-grid-column>
                <vaadin-grid-column header="Description" flex-grow="1" resizable
                    ${columnBodyRenderer(f => full(f.description), [])}></vaadin-grid-column>
                <vaadin-grid-column header="Enabled" auto-width
                    ${columnBodyRenderer(f => html`<vaadin-checkbox .checked="${live(f.enabled)}" ?disabled="${this._busy}"
                        @change="${e => this._set(f.key, e.target.checked)}"></vaadin-checkbox>`, [this._busy])}></vaadin-grid-column>
                <vaadin-grid-column header="Source" auto-width
                    ${columnBodyRenderer(f => html`${f.source}`, [])}></vaadin-grid-column>
                <vaadin-grid-column header="" auto-width
                    ${columnBodyRenderer(f => f.source === 'site'
                        ? html`<vaadin-button theme="small" ?disabled="${this._busy}"
                            title="Remove the site's own switch: the network default or the module's default applies"
                            @click="${() => this._reset(f.key)}">Reset</vaadin-button>`
                        : '', [this._busy])}></vaadin-grid-column>
            </vaadin-grid>
            ${this._loaded && this._features.length === 0 ? html`<div>No module declares a per-site feature.</div>` : ''}
            ${this._message == null ? '' : html`<div>${this._message}</div>`}
            ${this._error == null ? '' : html`<div class="error">${this._error}</div>`}`;
    }

    _set(key, enabled) {
        if (this._busy) {
            return;
        }
        this._busy = true;
        this._error = null;
        this._message = null;
        this.jsonRpc.setFeature({ siteId: this._site, key: key, enabled: enabled })
            .then(r => {
                const answer = r.result;
                if (answer == null || answer.error != null) {
                    this._error = answer == null ? 'No answer.' : answer.error;
                    this._load();
                    return;
                }
                const feature = answer.feature;
                this._message = `${feature.key} is now ${feature.enabled ? 'on' : 'off'} (source: ${feature.source})`;
                this._features = this._features.map(f => f.key === feature.key ? feature : f);
            })
            .catch(e => { this._error = errorText(e); this._load(); })
            .finally(() => { this._busy = false; });
    }

    _reset(key) {
        if (this._busy) {
            return;
        }
        this._busy = true;
        this._error = null;
        this._message = null;
        this.jsonRpc.clearFeature({ siteId: this._site, key: key })
            .then(r => {
                const answer = r.result;
                if (answer == null || answer.error != null) {
                    this._error = answer == null ? 'No answer.' : answer.error;
                    return;
                }
                const feature = answer.feature;
                this._message = `${feature.key} is back to ${feature.enabled ? 'on' : 'off'} (source: ${feature.source})`;
            })
            .catch(e => { this._error = errorText(e); })
            .finally(() => { this._busy = false; this._load(); });
    }
}
customElements.define('qwc-quarkpress-features', QwcQuarkpressFeatures);
