import { LitElement, html, css } from 'lit';
import { JsonRpc } from 'jsonrpc';
import { columnBodyRenderer } from '@vaadin/grid/lit.js';
import '@vaadin/grid';

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

function yesNo(value) {
    return value ? 'yes' : 'no';
}

/** The registries of the current site: post types, post statuses, taxonomies and roles with their capabilities. */
export class QwcQuarkpressRegistries extends LitElement {

    jsonRpc = new JsonRpc(this);

    static styles = css`
        :host { display: flex; flex-direction: column; gap: 10px; padding: 0 10px 10px 10px; }
        vaadin-grid { height: auto; }
        .error { color: var(--lumo-error-text-color); }
        .notice { color: var(--lumo-warning-text-color, var(--lumo-secondary-text-color)); }
        table { border-collapse: collapse; font-size: var(--lumo-font-size-s); }
        th, td { border: 1px solid var(--lumo-contrast-10pct); padding: 2px 8px; text-align: left; }
        td.denied { color: var(--lumo-error-text-color); }
        .matrix { overflow: auto; }
    `;

    static properties = {
        _site: { state: true },
        _notice: { state: true },
        _registries: { state: true },
        _error: { state: true }
    };

    connectedCallback() {
        super.connectedCallback();
        this._site = currentSite();
        this._notice = null;
        this._registries = null;
        this._error = null;
        checkedSite(this.jsonRpc).then(checked => {
            this._site = checked.site;
            this._notice = checked.notice;
            this.jsonRpc.registries({ siteId: this._site })
                .then(r => { this._registries = r.result; })
                .catch(e => { this._error = errorText(e); });
        });
    }

    _heading() {
        return html`
            ${this._notice == null ? '' : html`<div class="notice">${this._notice}</div>`}
            <div>Site ${this._site}</div>`;
    }

    render() {
        if (this._error != null) {
            return html`${this._heading()}<div class="error">${this._error}</div>`;
        }
        if (this._registries == null) {
            return this._heading();
        }
        const r = this._registries;
        return html`
            ${this._heading()}
            <h3>Post types</h3>
            <vaadin-grid .items="${r.postTypes == null ? [] : r.postTypes}" all-rows-visible theme="no-border">
                <vaadin-grid-column header="Key" auto-width ${columnBodyRenderer(t => full(t.key), [])}></vaadin-grid-column>
                <vaadin-grid-column header="Label" auto-width ${columnBodyRenderer(t => full(t.label), [])}></vaadin-grid-column>
                <vaadin-grid-column header="Hierarchical" auto-width ${columnBodyRenderer(t => html`${yesNo(t.hierarchical)}`, [])}></vaadin-grid-column>
                <vaadin-grid-column header="Public" auto-width ${columnBodyRenderer(t => html`${yesNo(t.publicly)}`, [])}></vaadin-grid-column>
                <vaadin-grid-column header="Supports" resizable ${columnBodyRenderer(t => full((t.supports == null ? [] : t.supports).join(', ')), [])}></vaadin-grid-column>
                <vaadin-grid-column header="Built in" auto-width ${columnBodyRenderer(t => html`${yesNo(t.builtIn)}`, [])}></vaadin-grid-column>
            </vaadin-grid>
            <h3>Post statuses</h3>
            <vaadin-grid .items="${r.statuses == null ? [] : r.statuses}" all-rows-visible theme="no-border">
                <vaadin-grid-column header="Name" auto-width ${columnBodyRenderer(s => html`${s.name}`, [])}></vaadin-grid-column>
                <vaadin-grid-column header="Label" auto-width ${columnBodyRenderer(s => full(s.label), [])}></vaadin-grid-column>
                <vaadin-grid-column header="Public" auto-width ${columnBodyRenderer(s => html`${yesNo(s.publicly)}`, [])}></vaadin-grid-column>
                <vaadin-grid-column header="Private" auto-width ${columnBodyRenderer(s => html`${yesNo(s.privateStatus)}`, [])}></vaadin-grid-column>
                <vaadin-grid-column header="Internal" auto-width ${columnBodyRenderer(s => html`${yesNo(s.internal)}`, [])}></vaadin-grid-column>
                <vaadin-grid-column header="Built in" auto-width ${columnBodyRenderer(s => html`${yesNo(s.builtIn)}`, [])}></vaadin-grid-column>
            </vaadin-grid>
            ${r.taxonomies == null ? '' : html`
                <h3>Taxonomies</h3>
                <vaadin-grid .items="${r.taxonomies}" all-rows-visible theme="no-border">
                    <vaadin-grid-column header="Key" auto-width ${columnBodyRenderer(t => full(t.key), [])}></vaadin-grid-column>
                    <vaadin-grid-column header="Label" auto-width ${columnBodyRenderer(t => full(t.label), [])}></vaadin-grid-column>
                    <vaadin-grid-column header="Object types" auto-width ${columnBodyRenderer(t => full((t.objectTypes == null ? [] : t.objectTypes).join(', ')), [])}></vaadin-grid-column>
                    <vaadin-grid-column header="Hierarchical" auto-width ${columnBodyRenderer(t => html`${yesNo(t.hierarchical)}`, [])}></vaadin-grid-column>
                    <vaadin-grid-column header="Built in" auto-width ${columnBodyRenderer(t => html`${yesNo(t.builtIn)}`, [])}></vaadin-grid-column>
                </vaadin-grid>`}
            <h3>Roles</h3>
            ${this._roles(r.roles == null ? [] : r.roles)}`;
    }

    _roles(roles) {
        const keys = new Set();
        for (const role of roles) {
            for (const capability of Object.keys(role.capabilities == null ? {} : role.capabilities)) {
                keys.add(capability);
            }
        }
        const capabilities = [...keys].sort();
        return html`
            <div class="matrix">
                <table>
                    <thead>
                        <tr><th>Capability</th>${roles.map(role => html`<th title="${role.key}">${role.name}</th>`)}</tr>
                    </thead>
                    <tbody>
                        ${capabilities.map(capability => html`
                            <tr><td>${capability}</td>${roles.map(role => this._cell(role, capability))}</tr>`)}
                    </tbody>
                </table>
            </div>`;
    }

    _cell(role, capability) {
        const value = role.capabilities == null ? undefined : role.capabilities[capability];
        if (value == null) {
            return html`<td></td>`;
        }
        return value ? html`<td>yes</td>` : html`<td class="denied">denied</td>`;
    }
}
customElements.define('qwc-quarkpress-registries', QwcQuarkpressRegistries);
