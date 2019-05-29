import {Component, OnInit} from '@angular/core';
import {Router} from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import {UserService, FormDataService} from 'mgmt-lib';

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html'
})

export class AppComponent implements OnInit {
  constructor(public router: Router,
              public user: UserService,
              public dialog: MatDialog,
              public formData: FormDataService) {
    this.user.getUser().subscribe();
  }

  ngOnInit() {
  }
}
