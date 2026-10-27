import { LitElement, html, css } from 'lit';
import { JsonRpc } from 'jsonrpc';
import { columnBodyRenderer } from '@vaadin/grid/lit.js';
import * as buildTimeData from 'build-time-data';
import '@vaadin/grid';

function errorText(e) {
    if (e != null && e.error != null && e.error.message != null) {
        return e.error.message;
    }
    return String(e);
}

/** The users of the dev realm, their QuarkPress profile and their roles per site. */
export class QwcQuarkpressUsers extends LitElement {

    jsonRpc = new JsonRpc(this);

    static styles = css`
        :host { display: flex; flex-direction: column; gap: 10px; height: 100%; padding: 0 10px; }
        vaadin-grid { flex: 1; }
        .error { color: var(--lumo-error-text-color); }
        .none { color: var(--lumo-contrast-50pct); }
    `;

    static properties = { _users: { state: true }, _error: { state: true } };

    constructor() {
        super();
        this._users = [];
        this._error = null;
    }

    connectedCallback() {
        super.connectedCallback();
        this.jsonRpc.devUsers()
            .then(r => { this._users = r.result == null ? [] : r.result; })
            .catch(e => { this._error = errorText(e); });
    }

    render() {
        const tokenPage = buildTimeData.tokenPage;
        return html`
            <div>
                ${tokenPage == null
                    ? html`Tokens for these users need Keycloak Dev Services.`
                    : html`<a href="${tokenPage}">Get a token</a>`}
                Each user's password is its user name.
            </div>
            ${this._error == null ? '' : html`<div class="error">${this._error}</div>`}
            <vaadin-grid .items="${this._users}" theme="no-border row-stripes">
                <vaadin-grid-column header="User name" auto-width ${columnBodyRenderer(u => html`${u.username}`, [])}></vaadin-grid-column>
                <vaadin-grid-column header="Subject" auto-width resizable ${columnBodyRenderer(u => html`${u.subject}`, [])}></vaadin-grid-column>
                <vaadin-grid-column header="Realm roles" auto-width ${columnBodyRenderer(u => this._list(u.realmRoles), [])}></vaadin-grid-column>
                <vaadin-grid-column header="Profile" auto-width ${columnBodyRenderer(u => html`${u.hasProfile ? 'yes' : 'no'}`, [])}></vaadin-grid-column>
                <vaadin-grid-column header="Network admin" auto-width ${columnBodyRenderer(u => html`${u.networkAdmin ? 'yes' : 'no'}`, [])}></vaadin-grid-column>
                <vaadin-grid-column header="Site roles" resizable ${columnBodyRenderer(u => this._siteRoles(u.siteRoles), [])}></vaadin-grid-column>
            </vaadin-grid>`;
    }

    _list(values) {
        if (values == null || values.length === 0) {
            return html`<span class="none">none</span>`;
        }
        return html`${values.join(', ')}`;
    }

    _siteRoles(siteRoles) {
        if (siteRoles == null || siteRoles.length === 0) {
            return html`<span class="none">none</span>`;
        }
        return html`${siteRoles.map(s => `${s.site}: ${(s.roles == null ? [] : s.roles).join(', ')}`).join('; ')}`;
    }
}
customElements.define('qwc-quarkpress-users', QwcQuarkpressUsers);
